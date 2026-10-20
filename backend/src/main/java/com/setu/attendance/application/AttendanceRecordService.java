package com.setu.attendance.application;

import com.setu.attendance.domain.AttendanceRecord;
import com.setu.attendance.domain.AttendanceRecordStatus;
import com.setu.attendance.domain.AttendanceRecordedEvent;
import com.setu.attendance.domain.AttendanceSession;
import com.setu.attendance.domain.AttendanceSessionStatus;
import com.setu.attendance.domain.AttendanceSource;
import com.setu.attendance.domain.CheckInLocation;
import com.setu.attendance.infrastructure.AttendanceQrSessionRepository;
import com.setu.attendance.infrastructure.AttendanceRecordInsertGuard;
import com.setu.attendance.infrastructure.AttendanceRecordRepository;
import com.setu.attendance.infrastructure.AttendanceSessionRepository;
import com.setu.attendance.infrastructure.QrTokenHasher;
import com.setu.shared.academic.AcademicDirectory;
import com.setu.shared.websocket.LiveBroadcastRequest;
import org.springframework.context.ApplicationEventPublisher;
import com.setu.shared.authorization.AuthorizationContext;
import com.setu.shared.authorization.ResourceRef;
import com.setu.shared.governance.AuditLogWriter;
import com.setu.shared.governance.OutboxEventWriter;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The authoritative student scan flow (Day 9 §4) and manual correction
 * (Day 9 §10). Per transaction-concurrency-matrix.md scenario 3, the
 * composite unique {@code (attendance_session_id, enrollment_id)} IS the
 * concurrency control for duplicate scans — no explicit row lock is taken
 * on the session for an ordinary scan (unlike admin session
 * open/close/rotate in {@link AttendanceSessionService}, which do lock):
 * over-locking here would needlessly serialize unrelated students' scans
 * against each other, which Day 9 §14 TEST 2 explicitly expects to both
 * succeed. A losing INSERT is caught and its existing row returned
 * idempotently (never a raw exception).
 * <p>
 * Race with session close (Day 9 §14 TEST 3): whichever transaction commits
 * first wins — either the scan's {@code PRESENT} row, or the close's
 * system-generated {@code ABSENT} row for that same enrollment — the
 * composite unique constraint guarantees exactly one, never zero, never
 * two, never a partial row either way. This is a genuine, disclosed
 * timing-dependent outcome — it is never the case that the scan always
 * wins; see the class javadoc's isolation note.
 * <p>
 * {@code @NamedInterface} added Day 13: {@code risk.application.RiskFeatureService}
 * calls {@link #percentageForEnrollmentUnchecked} once per one of a student's active
 * enrollments (resolved via {@code enrollment.application.EnrollmentService},
 * itself already a NamedInterface) to build the attendance-trend feature —
 * that method deliberately has no authorization of its own (see its own
 * javadoc), matching the narrow-port precedent every other cross-module
 * read in this codebase already follows; the caller's own entry point is
 * what's authorization-gated, not this internal aggregation step. The
 * HTTP-facing {@link #percentageForEnrollment} (Day 16 §student) wraps it
 * with a real self-or-assigned-faculty-or-admin check instead — see that
 * method's own javadoc for the IDOR this fixed.
 */
@org.springframework.modulith.NamedInterface
@Service
public class AttendanceRecordService {

    /** See {@code AttendanceSessionService.MANAGE_PERMISSION}'s javadoc for why this is NOT the FACULTY-held {@code MANAGE_ATTENDANCE}. */
    private static final String MANAGE_PERMISSION = "MANAGE_ATTENDANCE_ALL_SECTIONS";
    private static final String VIEW_PERMISSION = "VIEW_TENANT_DATA";
    private static final List<AttendanceRecordStatus> PRESENT_LIKE = List.of(AttendanceRecordStatus.PRESENT, AttendanceRecordStatus.LATE);

    private final AttendanceSessionRepository attendanceSessionRepository;
    private final AttendanceQrSessionRepository attendanceQrSessionRepository;
    private final AttendanceRecordRepository attendanceRecordRepository;
    private final AttendanceRecordInsertGuard attendanceRecordInsertGuard;
    private final QrTokenHasher qrTokenHasher;
    private final AcademicDirectory academic;
    private final ApplicationEventPublisher eventPublisher;
    private final AuthorizationContext authorizationContext;
    private final AuditLogWriter auditLogWriter;
    private final OutboxEventWriter outboxEventWriter;

    public AttendanceRecordService(AttendanceSessionRepository attendanceSessionRepository,
                                    AttendanceQrSessionRepository attendanceQrSessionRepository,
                                    AttendanceRecordRepository attendanceRecordRepository,
                                    AttendanceRecordInsertGuard attendanceRecordInsertGuard, QrTokenHasher qrTokenHasher,
                                    AcademicDirectory academic,
                                    ApplicationEventPublisher eventPublisher, AuthorizationContext authorizationContext,
                                    AuditLogWriter auditLogWriter, OutboxEventWriter outboxEventWriter) {
        this.attendanceSessionRepository = attendanceSessionRepository;
        this.attendanceQrSessionRepository = attendanceQrSessionRepository;
        this.attendanceRecordRepository = attendanceRecordRepository;
        this.attendanceRecordInsertGuard = attendanceRecordInsertGuard;
        this.qrTokenHasher = qrTokenHasher;
        this.academic = academic;
        this.eventPublisher = eventPublisher;
        this.authorizationContext = authorizationContext;
        this.auditLogWriter = auditLogWriter;
        this.outboxEventWriter = outboxEventWriter;
    }

    public sealed interface ScanResult {
        /** @param locationStatus how the reported position compared with the classroom; informational, null only for callers that predate it */
        record Success(UUID recordId, CheckInLocation locationStatus) implements ScanResult {
            public Success(UUID recordId) {
                this(recordId, null);
            }
        }

        record AlreadyRecorded(UUID recordId, AttendanceRecordStatus status) implements ScanResult {
        }

        record SessionNotFound() implements ScanResult {
        }

        record StudentNotFound() implements ScanResult {
        }

        record SessionNotOpen() implements ScanResult {
        }

        record InvalidToken() implements ScanResult {
        }

        record ExpiredToken() implements ScanResult {
        }

        record NotEnrolled() implements ScanResult {
        }
    }

    /**
     * The position a student's device reported at check-in, all optional. Compared with the session's classroom position and then dropped: only the
     * outcome (status, distance, accuracy) is stored, never these coordinates.
     */
    public record LocationReport(Double latitude, Double longitude, Double accuracyMeters) {
    }

    /** A scan that reports no position (the seeder, older clients): the check-in counts and its location is recorded as {@code NO_LOCATION}. */
    @Transactional
    public ScanResult scan(UUID sessionId, String rawToken) {
        return scan(sessionId, rawToken, null);
    }

    /** Authenticated student scan (Day 9 §4). The student is never a request parameter — always resolved from the caller's own principal, so a student can only ever mark their own attendance. Where the check-in came from is recorded for the instructor and never decides whether it counts. */
    @Transactional
    public ScanResult scan(UUID sessionId, String rawToken, LocationReport location) {
        Optional<AttendanceSession> maybeSession = attendanceSessionRepository.findById(sessionId);
        if (maybeSession.isEmpty()) {
            return new ScanResult.SessionNotFound();
        }
        AttendanceSession session = maybeSession.get();

        UUID currentUserId = authorizationContext.currentUserId();
        Optional<UUID> studentId = academic.studentIdOfUser(session.tenantId(), currentUserId);
        if (studentId.isEmpty()) {
            return new ScanResult.StudentNotFound();
        }

        if (!session.isOpen()) {
            return new ScanResult.SessionNotOpen();
        }

        if (!qrTokenHasher.hash(rawToken).equals(session.tokenHash())) {
            return new ScanResult.InvalidToken();
        }
        Optional<com.setu.attendance.domain.AttendanceQrSession> latestQr = attendanceQrSessionRepository.findLatest(sessionId);
        if (latestQr.isEmpty() || latestQr.get().isExpired(Instant.now())) {
            return new ScanResult.ExpiredToken();
        }

        Optional<UUID> enrollmentId = academic.activeEnrollmentId(session.sectionId(), studentId.get());
        if (enrollmentId.isEmpty()) {
            return new ScanResult.NotEnrolled();
        }

        Optional<AttendanceRecord> existing = attendanceRecordRepository.findByAttendanceSessionIdAndEnrollmentId(sessionId, enrollmentId.get());
        if (existing.isPresent()) {
            return new ScanResult.AlreadyRecorded(existing.get().id(), existing.get().status());
        }

        // No documented LATE-vs-PRESENT grace-period threshold exists anywhere in the canonical spec — disclosed
        // smallest defensible decision: every successful QR scan during an OPEN session marks PRESENT; LATE remains
        // reachable only via manual correction (faculty/admin judgment), never an invented numeric cutoff.
        Instant now = Instant.now();
        AttendanceRecord record = new AttendanceRecord(UUID.randomUUID(), sessionId, enrollmentId.get(),
            AttendanceRecordStatus.PRESENT, now, AttendanceSource.QR_SCAN);
        CheckInGeo.Outcome where = CheckInGeo.assess(session.anchorLatitude(), session.anchorLongitude(),
            location == null ? null : location.latitude(), location == null ? null : location.longitude(),
            location == null ? null : location.accuracyMeters());
        record.checkInLocation(where.status(), where.distanceMeters(), where.accuracyMeters());
        // The insert runs in its own REQUIRES_NEW transaction (see AttendanceRecordInsertGuard's javadoc) so a lost
        // race against a concurrent duplicate scan (transaction-concurrency-matrix.md scenario 3) only aborts that
        // one separate transaction — this (outer) transaction, and the follow-up read below, stay usable.
        try {
            attendanceRecordInsertGuard.insert(record);
        } catch (DataIntegrityViolationException e) {
            AttendanceRecord winner = attendanceRecordRepository
                .findByAttendanceSessionIdAndEnrollmentId(sessionId, enrollmentId.get()).orElseThrow();
            return new ScanResult.AlreadyRecorded(winner.id(), winner.status());
        }

        auditLogWriter.write(session.tenantId(), currentUserId, "ATTENDANCE_RECORDED", "attendance.attendance_records",
            record.id(), Map.of("sessionId", sessionId));

        // Day 15: closes the Day 9-disclosed gap ("wire the transactional outbox for AttendanceRecorded" was
        // never actually done). Ordering key is sessionId, not the record's own id, per kafka-event-catalog.md's
        // explicit exception for this event ("per-session order matters for the live board").
        UUID correlationId = UUID.randomUUID();
        var event = new AttendanceRecordedEvent(UUID.randomUUID(), now, session.tenantId(), sessionId, correlationId,
            null, record.id(), sessionId, enrollmentId.get(), record.status().name(), now);
        outboxEventWriter.write("attendance_records", sessionId, event.eventType(), event.eventVersion(), event,
            session.tenantId(), correlationId, null);

        broadcastUpdate(session);
        return new ScanResult.Success(record.id(), where.status());
    }

    /** Day 9 §9: minimal, non-PII payload — a refresh signal, not a roster. The instructor UI re-fetches full roster detail via the authoritative REST GET, never trusting the socket payload as proof of commit. */
    private void broadcastUpdate(AttendanceSession session) {
        long presentCount = attendanceRecordRepository.findByAttendanceSessionId(session.id()).stream()
            .filter(r -> PRESENT_LIKE.contains(r.status())).count();
        record LiveUpdate(UUID sessionId, UUID sectionId, long presentCount, Instant updatedAt) {
        }
        eventPublisher.publishEvent(new LiveBroadcastRequest("/topic/attendance/section/" + session.sectionId(),
            new LiveUpdate(session.id(), session.sectionId(), presentCount, Instant.now())));
    }

    /** Assigned-faculty-or-admin per the Day 9 authorization matrix (faculty limited to their own assigned sections; college admin tenant-wide) — the same idiom as {@link AttendanceSessionService}. */
    @Transactional(readOnly = true)
    public List<AttendanceRecord> listForSession(UUID sessionId, UUID tenantId) {
        AttendanceSession session = attendanceSessionRepository.findById(sessionId)
            .orElseThrow(() -> new IllegalArgumentException("attendance session not found"));
        UUID currentUserId = authorizationContext.currentUserId();
        Optional<UUID> facultyId = academic.facultyIdOfUser(tenantId, currentUserId);
        boolean isAssignedFaculty = facultyId.isPresent() && academic.isActivelyAssigned(session.sectionId(), facultyId.get())
            && authorizationContext.holdsFacultyRole(tenantId);
        if (!isAssignedFaculty) {
            authorizationContext.assertCan(VIEW_PERMISSION, ResourceRef.of("attendance_record", UUID.randomUUID(), tenantId));
        }
        return attendanceRecordRepository.findByAttendanceSessionId(sessionId);
    }

    public record AttendancePercentage(long presentOrLate, long total) {
        public double percentage() {
            return total == 0 ? 0.0 : (presentOrLate * 100.0) / total;
        }
    }

    /**
     * Derived on every call from attendance_records — never a stored aggregate column (Day 9 §5 explicit rule).
     * <p>
     * Day 16 §student: real IDOR found live — the HTTP-facing {@code GET /attendance/enrollments/{id}/percentage}
     * endpoint called straight into this method with NO authorization check at all, letting any authenticated
     * user (any tenant, any role) read any OTHER student's attendance percentage by enrollment id alone. The
     * class-level {@link #percentageForEnrollmentUnchecked} javadoc's "no authorization of its own" note was
     * written for the cross-module RiskFeatureService caller, which already authorizes its own outer operation —
     * it was never meant to cover a raw, unauthenticated HTTP path. This method now does the same
     * self-or-assigned-faculty-or-admin check every other student-facing read in this codebase uses; the
     * unchecked cross-module variant is kept, separately named, for the one caller that has already authorized
     * itself.
     */
    @Transactional(readOnly = true)
    public AttendancePercentage percentageForEnrollment(UUID enrollmentId) {
        Optional<AcademicDirectory.EnrollmentRef> summary =
            academic.enrollment(enrollmentId);
        if (summary.isEmpty()) {
            return new AttendancePercentage(0, 0);
        }
        UUID tenantId = summary.get().tenantId();
        UUID currentUserId = authorizationContext.currentUserId();
        Optional<AcademicDirectory.TenantOwner> studentInfo =
            academic.studentOwner(summary.get().studentId());
        boolean isSelf = studentInfo.isPresent() && currentUserId.equals(studentInfo.get().ownerUserId());
        if (!isSelf) {
            UUID facultyId = academic.facultyIdOfUser(tenantId, currentUserId).orElse(null);
            boolean isAssignedFaculty = facultyId != null && academic.isActivelyAssigned(summary.get().sectionId(), facultyId)
                && authorizationContext.holdsFacultyRole(tenantId);
            if (!isAssignedFaculty
                && !authorizationContext.can(VIEW_PERMISSION, ResourceRef.of("attendance_record", enrollmentId, tenantId)).isAllowed()) {
                // Falls through to a guardian with an active VIEW_ATTENDANCE delegation for this exact student
                // (GuardianAccessService's supported-permissions set) — same delegatableForStudent idiom as
                // GradeService/RiskCalculationService/LearningProgressService/StudentAccountService.
                UUID ownerUserId = studentInfo.map(AcademicDirectory.TenantOwner::ownerUserId).orElse(null);
                ResourceRef delegated = ResourceRef.of("attendance_record", enrollmentId, tenantId)
                    .ownedBy(ownerUserId).delegatableForStudent(summary.get().studentId());
                authorizationContext.assertCan("VIEW_ATTENDANCE", delegated);
            }
        }
        return percentageForEnrollmentUnchecked(enrollmentId);
    }

    /** No authorization of its own — see {@link #percentageForEnrollment}'s javadoc. Only for callers (e.g. {@code risk.application.RiskFeatureService}) that have already authorized their own outer operation. */
    @Transactional(readOnly = true)
    public AttendancePercentage percentageForEnrollmentUnchecked(UUID enrollmentId) {
        long presentOrLate = attendanceRecordRepository.countByEnrollmentIdAndStatusIn(enrollmentId, PRESENT_LIKE);
        long total = attendanceRecordRepository.countByEnrollmentId(enrollmentId);
        return new AttendancePercentage(presentOrLate, total);
    }

    public sealed interface CorrectResult {
        record Success() implements CorrectResult {
        }

        record NotFound() implements CorrectResult {
        }

        record SessionStillOpen() implements CorrectResult {
        }

        /** transaction-concurrency-matrix.md scenario 4: the losing writer of two concurrent corrections gets this — never a silently overwritten value, never a raw Hibernate exception leaking to the caller. */
        record VersionConflict() implements CorrectResult {
        }
    }

    /** Day 9 §10: only after the enclosing session is CLOSED (docs/state-machines.md #15) — an OPEN session's records are still live and shouldn't need "correcting" yet. */
    @Transactional
    public CorrectResult correct(UUID recordId, AttendanceRecordStatus newStatus, String reason) {
        Optional<AttendanceRecord> maybeRecord = attendanceRecordRepository.findById(recordId);
        if (maybeRecord.isEmpty()) {
            return new CorrectResult.NotFound();
        }
        AttendanceRecord record = maybeRecord.get();
        AttendanceSession session = attendanceSessionRepository.findById(record.attendanceSessionId()).orElseThrow();

        UUID currentUserId = authorizationContext.currentUserId();
        Optional<UUID> facultyId = academic.facultyIdOfUser(session.tenantId(), currentUserId);
        boolean isAssignedFaculty = facultyId.isPresent() && academic.isActivelyAssigned(session.sectionId(), facultyId.get())
            && authorizationContext.holdsFacultyRole(session.tenantId());
        if (!isAssignedFaculty) {
            authorizationContext.assertCan(MANAGE_PERMISSION, ResourceRef.of("attendance_record", record.id(), session.tenantId()));
        }

        if (session.status() != AttendanceSessionStatus.CLOSED) {
            return new CorrectResult.SessionStillOpen();
        }

        AttendanceRecordStatus previous = record.status();
        // Version-checked conditional UPDATE in its own REQUIRES_NEW transaction (see AttendanceRecordInsertGuard's
        // javadoc for why a load-then-save of THIS managed entity doesn't work here). record.version() is the
        // version this method originally read — a genuine optimistic-lock check against a concurrent correction.
        boolean updated = attendanceRecordInsertGuard.update(recordId, newStatus, currentUserId, record.version());
        if (!updated) {
            return new CorrectResult.VersionConflict();
        }
        auditLogWriter.write(session.tenantId(), currentUserId, "ATTENDANCE_CORRECTED", "attendance.attendance_records",
            record.id(), Map.of("from", previous.name(), "to", newStatus.name(), "reason", reason == null ? "" : reason));
        return new CorrectResult.Success();
    }
}
