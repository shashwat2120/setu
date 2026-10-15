package com.setu.attendance.application;

import com.setu.attendance.domain.AttendanceRecord;
import com.setu.attendance.domain.AttendanceRecordStatus;
import com.setu.attendance.domain.AttendanceSession;
import com.setu.attendance.domain.AttendanceSessionStatus;
import com.setu.attendance.domain.AttendanceSource;
import com.setu.attendance.infrastructure.AttendanceQrSessionRepository;
import com.setu.attendance.infrastructure.AttendanceRecordInsertGuard;
import com.setu.attendance.infrastructure.AttendanceRecordRepository;
import com.setu.attendance.infrastructure.AttendanceSessionInsertGuard;
import com.setu.attendance.infrastructure.AttendanceSessionRepository;
import com.setu.attendance.infrastructure.QrTokenHasher;
import com.setu.shared.academic.AcademicDirectory;
import com.setu.shared.authorization.AuthorizationContext;
import com.setu.shared.authorization.ResourceRef;
import com.setu.shared.enrollment.CourseSectionDirectory;
import com.setu.shared.governance.AuditLogWriter;
import com.setu.shared.governance.OutboxEventWriter;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Attendance session lifecycle (Day 9 §2-3) — open, close (with automatic
 * ABSENT generation), and QR rotation. Transitions follow
 * docs/state-machines.md #14 exactly: {@code OPEN -> CLOSED} only,
 * terminal, no reopen.
 * <p>
 * Authorization follows the established Day 8 "self-or-admin" idiom, here
 * "assigned-faculty-or-admin": {@link #isAssignedFacultyOrAdmin} resolves
 * whether the current user is the actively-assigned faculty for the
 * section (a real assignment-data check via
 * {@code directory.FacultyProfileService}/{@code enrollment.
 * FacultyAssignmentService}, never {@code if (role == FACULTY)}) and only
 * falls back to the tenant-wide {@code MANAGE_ATTENDANCE} permission check
 * when that's false — exactly the pattern Day 9 §7 requires.
 * <p>
 * QR security model (docs/contradiction-audit.md #9): a fresh raw token is
 * generated on open and on every rotation, its SHA-256 hash overwrites
 * {@link AttendanceSession#tokenHash}, and the raw value is returned to the
 * caller ONLY in the HTTP response — never persisted, never logged, never
 * placed in an audit payload. Get-active-QR always performs a fresh
 * rotation rather than replaying a previously-issued token (a disclosed
 * simplification — there is nowhere to durably cache a raw token without
 * contradicting the never-persist rule, and Redis is explicitly out of
 * Day 9's scope).
 */
@org.springframework.modulith.NamedInterface // Day 15 §demo-data: com.setu.shared.seed.DemoDataSeeder needs this cross-module
@Service
public class AttendanceSessionService {

    /**
     * Deliberately NOT {@code MANAGE_ATTENDANCE} for this fallback check:
     * that permission already exists (V54/V55, Day 4) and is granted
     * tenant-wide to FACULTY (not scoped to any specific section), so
     * reusing it here would let ANY faculty member pass this "admin,
     * any section" fallback — defeating the assigned-faculty-only
     * restriction Day 9 §7 requires. {@code MANAGE_ATTENDANCE_ALL_SECTIONS}
     * (V63) is granted only to SUPER_ADMIN/COLLEGE_ADMIN.
     */
    private static final String MANAGE_PERMISSION = "MANAGE_ATTENDANCE_ALL_SECTIONS";
    /** No documented QR rotation interval exists anywhere in the canonical spec ("short-lived" is qualitative only) — disclosed interim default, not an arbitrary invention: short enough that a screenshot/photo of the QR is useless within seconds, long enough for a real scan-and-submit round trip. */
    private static final long QR_TTL_SECONDS = 30;

    private final AttendanceSessionRepository attendanceSessionRepository;
    private final AttendanceQrSessionRepository attendanceQrSessionRepository;
    private final AttendanceRecordRepository attendanceRecordRepository;
    private final AttendanceRecordInsertGuard attendanceRecordInsertGuard;
    private final AttendanceSessionInsertGuard attendanceSessionInsertGuard;
    private final QrTokenHasher qrTokenHasher;
    private final CourseSectionDirectory courseSectionDirectory;
    private final AcademicDirectory academic;
    private final AuthorizationContext authorizationContext;
    private final AuditLogWriter auditLogWriter;
    private final OutboxEventWriter outboxEventWriter;

    public AttendanceSessionService(AttendanceSessionRepository attendanceSessionRepository,
                                     AttendanceQrSessionRepository attendanceQrSessionRepository,
                                     AttendanceRecordRepository attendanceRecordRepository,
                                     AttendanceRecordInsertGuard attendanceRecordInsertGuard,
                                     AttendanceSessionInsertGuard attendanceSessionInsertGuard, QrTokenHasher qrTokenHasher,
                                     CourseSectionDirectory courseSectionDirectory, AcademicDirectory academic,
                                     AuthorizationContext authorizationContext,
                                     AuditLogWriter auditLogWriter, OutboxEventWriter outboxEventWriter) {
        this.outboxEventWriter = outboxEventWriter;
        this.attendanceSessionRepository = attendanceSessionRepository;
        this.attendanceQrSessionRepository = attendanceQrSessionRepository;
        this.attendanceRecordRepository = attendanceRecordRepository;
        this.attendanceRecordInsertGuard = attendanceRecordInsertGuard;
        this.attendanceSessionInsertGuard = attendanceSessionInsertGuard;
        this.qrTokenHasher = qrTokenHasher;
        this.courseSectionDirectory = courseSectionDirectory;
        this.academic = academic;
        this.authorizationContext = authorizationContext;
        this.auditLogWriter = auditLogWriter;
    }

    /** Real assignment-data check, never a role check — see class javadoc. */
    private boolean isAssignedFacultyOrAdmin(UUID sectionId, UUID tenantId) {
        UUID currentUserId = authorizationContext.currentUserId();
        Optional<UUID> facultyId = academic.facultyIdOfUser(tenantId, currentUserId);
        boolean isAssignedFaculty = facultyId.isPresent() && academic.isActivelyAssigned(sectionId, facultyId.get());
        if (!isAssignedFaculty) {
            authorizationContext.assertCan(MANAGE_PERMISSION, ResourceRef.of("attendance_session", UUID.randomUUID(), tenantId));
        }
        return true;
    }

    public sealed interface OpenResult {
        record Success(UUID sessionId, String rawToken, Instant qrExpiresAt) implements OpenResult {
        }

        record SectionNotFound() implements OpenResult {
        }

        record MeetingNotFound() implements OpenResult {
        }

        record MeetingSectionMismatch() implements OpenResult {
        }

        record TenantMismatch() implements OpenResult {
        }

        record InvalidSessionData(String message) implements OpenResult {
        }

        record SessionAlreadyOpenForMeeting() implements OpenResult {
        }
    }

    @Transactional
    public OpenResult open(UUID sectionId, UUID meetingId, Instant endsAt) {
        Optional<UUID> tenantId = courseSectionDirectory.findTenantId(sectionId);
        if (tenantId.isEmpty()) {
            return new OpenResult.SectionNotFound();
        }
        UUID tenant = tenantId.get();

        Optional<AcademicDirectory.MeetingRef> meetingInfo = academic.meeting(meetingId);
        if (meetingInfo.isEmpty()) {
            return new OpenResult.MeetingNotFound();
        }
        if (!meetingInfo.get().sectionId().equals(sectionId)) {
            return new OpenResult.MeetingSectionMismatch();
        }
        if (!meetingInfo.get().tenantId().equals(tenant)) {
            return new OpenResult.TenantMismatch();
        }

        isAssignedFacultyOrAdmin(sectionId, tenant);
        UUID currentUserId = authorizationContext.currentUserId();

        if (attendanceSessionRepository.findByMeetingIdAndStatus(meetingId, AttendanceSessionStatus.OPEN).isPresent()) {
            return new OpenResult.SessionAlreadyOpenForMeeting();
        }

        Instant now = Instant.now();
        String rawToken = qrTokenHasher.generateRawToken();
        String hash = qrTokenHasher.hash(rawToken);
        AttendanceSession session;
        try {
            session = new AttendanceSession(UUID.randomUUID(), tenant, sectionId, meetingId, currentUserId, now, endsAt, hash);
        } catch (IllegalArgumentException e) {
            return new OpenResult.InvalidSessionData(e.getMessage());
        }
        try {
            attendanceSessionInsertGuard.insert(session);
        } catch (DataIntegrityViolationException e) {
            return new OpenResult.SessionAlreadyOpenForMeeting();
        }
        Instant qrExpiresAt = now.plusSeconds(QR_TTL_SECONDS);
        attendanceQrSessionRepository.save(new com.setu.attendance.domain.AttendanceQrSession(
            UUID.randomUUID(), session.id(), qrTokenHasher.generateNonce(), qrExpiresAt, 0));

        auditLogWriter.write(tenant, currentUserId, "ATTENDANCE_SESSION_STARTED", "attendance.attendance_sessions",
            session.id(), Map.of("sectionId", sectionId, "meetingId", meetingId));
        UUID openedCorrelation = UUID.randomUUID();
        var openedEvent = new com.setu.attendance.domain.AttendanceSessionOpenedEvent(UUID.randomUUID(), now, tenant, session.id(), openedCorrelation, null,
            session.id(), sectionId, meetingId);
        outboxEventWriter.write("attendance_sessions", session.id(), openedEvent.eventType(), openedEvent.eventVersion(), openedEvent, tenant, openedCorrelation, null);
        return new OpenResult.Success(session.id(), rawToken, qrExpiresAt);
    }

    public sealed interface RotateResult {
        record Success(String rawToken, Instant qrExpiresAt) implements RotateResult {
        }

        record NotFound() implements RotateResult {
        }

        record SessionClosed() implements RotateResult {
        }
    }

    @Transactional
    public RotateResult rotateQr(UUID sessionId) {
        Optional<AttendanceSession> maybeSession = attendanceSessionRepository.findByIdForUpdate(sessionId);
        if (maybeSession.isEmpty()) {
            return new RotateResult.NotFound();
        }
        AttendanceSession session = maybeSession.get();
        isAssignedFacultyOrAdmin(session.sectionId(), session.tenantId());
        if (!session.isOpen()) {
            return new RotateResult.SessionClosed();
        }

        String rawToken = qrTokenHasher.generateRawToken();
        String hash = qrTokenHasher.hash(rawToken);
        session.rotateToken(hash);
        attendanceSessionRepository.save(session);

        Instant now = Instant.now();
        Instant qrExpiresAt = now.plusSeconds(QR_TTL_SECONDS);
        int nextRotation = attendanceQrSessionRepository.findMaxRotationNo(sessionId) + 1;
        attendanceQrSessionRepository.save(new com.setu.attendance.domain.AttendanceQrSession(
            UUID.randomUUID(), sessionId, qrTokenHasher.generateNonce(), qrExpiresAt, nextRotation));

        UUID actor = authorizationContext.currentUserId();
        auditLogWriter.write(session.tenantId(), actor, "ATTENDANCE_QR_ROTATED", "attendance.attendance_sessions",
            session.id(), Map.of("rotationNo", nextRotation));
        return new RotateResult.Success(rawToken, qrExpiresAt);
    }

    public sealed interface CloseResult {
        record Success(int absentRecordsGenerated) implements CloseResult {
        }

        record NotFound() implements CloseResult {
        }

        record InvalidTransition() implements CloseResult {
        }
    }

    @Transactional
    public CloseResult close(UUID sessionId) {
        Optional<AttendanceSession> maybeSession = attendanceSessionRepository.findByIdForUpdate(sessionId);
        if (maybeSession.isEmpty()) {
            return new CloseResult.NotFound();
        }
        AttendanceSession session = maybeSession.get();
        isAssignedFacultyOrAdmin(session.sectionId(), session.tenantId());
        if (!session.canTransitionTo(AttendanceSessionStatus.CLOSED)) {
            return new CloseResult.InvalidTransition();
        }
        session.transitionTo(AttendanceSessionStatus.CLOSED);
        attendanceSessionRepository.save(session);
        UUID actor = authorizationContext.currentUserId();
        auditLogWriter.write(session.tenantId(), actor, "ATTENDANCE_SESSION_ENDED", "attendance.attendance_sessions",
            session.id(), Map.of());

        // docs/state-machines.md #15: every enrolled student with no scan by close gets a system-generated ABSENT row.
        List<UUID> activeEnrollmentIds = academic.activeEnrollmentIds(session.sectionId());
        Set<UUID> alreadyMarked = new HashSet<>();
        for (AttendanceRecord existing : attendanceRecordRepository.findByAttendanceSessionId(sessionId)) {
            alreadyMarked.add(existing.enrollmentId());
        }
        Instant now = Instant.now();
        int generated = 0;
        for (UUID enrollmentId : activeEnrollmentIds) {
            if (alreadyMarked.contains(enrollmentId)) {
                continue;
            }
            // No "SYSTEM" source value exists in the V36 CHECK constraint (QR_SCAN/MANUAL/IMPORT only) — IMPORT is
            // the closest defensible fit for a system-generated, non-live, non-human-corrected row; disclosed in the Day 9 report.
            AttendanceRecord absent = new AttendanceRecord(UUID.randomUUID(), sessionId, enrollmentId,
                AttendanceRecordStatus.ABSENT, now, AttendanceSource.IMPORT);
            // REQUIRES_NEW insert (see AttendanceRecordInsertGuard's javadoc): a concurrent last-moment scan for
            // this same enrollment may have just committed its own PRESENT row — that race is legitimate and
            // documented (Day 9 §14 TEST 3), so losing it here must not abort the whole close() transaction or
            // skip generating ABSENT rows for every OTHER student.
            try {
                attendanceRecordInsertGuard.insert(absent);
                generated++;
            } catch (DataIntegrityViolationException e) {
                // Lost the race to a concurrent scan for this enrollment — that student already has a real record.
            }
        }
        UUID closedCorrelation = UUID.randomUUID();
        var closedEvent = new com.setu.attendance.domain.AttendanceSessionClosedEvent(UUID.randomUUID(), now, session.tenantId(), session.id(), closedCorrelation, null,
            session.id(), session.sectionId(), now, generated);
        outboxEventWriter.write("attendance_sessions", session.id(), closedEvent.eventType(), closedEvent.eventVersion(), closedEvent, session.tenantId(), closedCorrelation, null);
        return new CloseResult.Success(generated);
    }

    @Transactional(readOnly = true)
    public Optional<AttendanceSession> get(UUID sessionId) {
        return attendanceSessionRepository.findById(sessionId);
    }

    /** Day 16 §college-admin: every session opened for a section, newest first — assigned-faculty-or-admin gated, same as every other write/read on a specific section in this class. */
    @Transactional(readOnly = true)
    public java.util.List<AttendanceSession> listForSection(UUID sectionId) {
        Optional<UUID> tenantId = courseSectionDirectory.findTenantId(sectionId);
        if (tenantId.isEmpty()) {
            return java.util.List.of();
        }
        isAssignedFacultyOrAdmin(sectionId, tenantId.get());
        return attendanceSessionRepository.findBySectionIdOrderByStartedAtDesc(sectionId);
    }

    /** Day 15 §demo-data: plain-String unwrap of {@link #open} ("sessionId|rawToken") — a nested sealed-interface {@code Success} record cannot cross the module boundary directly, and this call site needs two values together. */
    @Transactional
    public String openForSeed(UUID sectionId, UUID meetingId, java.time.Instant endsAt) {
        return switch (open(sectionId, meetingId, endsAt)) {
            case OpenResult.Success s -> s.sessionId() + "|" + s.rawToken();
            default -> throw new IllegalStateException("seed attendance session open failed for section: " + sectionId);
        };
    }

}
