package com.setu.attendance.application;

import com.setu.attendance.domain.FacultyAttendance;
import com.setu.attendance.domain.FacultyAttendanceStatus;
import com.setu.attendance.domain.FacultyRegularisation;
import com.setu.attendance.domain.FacultyRegularisationEvent;
import com.setu.attendance.domain.RegularisationStatus;
import com.setu.attendance.infrastructure.FacultyAttendanceRepository;
import com.setu.attendance.infrastructure.FacultyRegularisationInsertGuard;
import com.setu.attendance.infrastructure.FacultyRegularisationRepository;
import com.setu.shared.academic.AcademicDirectory;
import com.setu.shared.authorization.AuthorizationContext;
import com.setu.shared.authorization.ResourceRef;
import com.setu.shared.governance.AuditLogWriter;
import com.setu.shared.governance.OutboxEventWriter;
import com.setu.shared.tenant.TenantContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * "I forgot to check in": a faculty member asks for a PAST day of their own attendance to be corrected, and an administrator
 * decides. Rules: docs/state-machines.md #26.
 * <ul>
 *   <li>Members act only on their own requests (and only while their profile is ACTIVE); administrators
 *       ({@code MANAGE_STAFF_ATTENDANCE}) decide, never on their own request.</li>
 *   <li>Only days strictly before today (today is what check-in is for) and within the configured window.</li>
 *   <li>Approving applies the correction to the attendance day in the SAME transaction (see
 *       {@link FacultyAttendanceService#applyRegularisation}); every step is audited and published to Kafka.</li>
 * </ul>
 */
@org.springframework.modulith.NamedInterface // shared.seed.DemoDataSeeder files an example request through this
@Service
public class FacultyRegularisationService {

    private static final String SELF_PERMISSION = "CHECK_IN_STAFF_ATTENDANCE";
    private static final String MANAGE_PERMISSION = "MANAGE_STAFF_ATTENDANCE";
    private static final String RESOURCE = "staff_attendance";
    private static final int MAX_REASON_LENGTH = 500;

    private final FacultyRegularisationRepository repository;
    private final FacultyRegularisationInsertGuard insertGuard;
    private final FacultyAttendanceRepository attendanceRepository;
    private final FacultyAttendanceService attendanceService;
    private final AcademicDirectory academic;
    private final TenantContext tenantContext;
    private final AuthorizationContext authorizationContext;
    private final AuditLogWriter auditLogWriter;
    private final OutboxEventWriter outboxEventWriter;
    private final int windowDays;

    public FacultyRegularisationService(FacultyRegularisationRepository repository,
                                         FacultyRegularisationInsertGuard insertGuard,
                                         FacultyAttendanceRepository attendanceRepository,
                                         FacultyAttendanceService attendanceService,
                                         AcademicDirectory academic,
                                         TenantContext tenantContext, AuthorizationContext authorizationContext,
                                         AuditLogWriter auditLogWriter, OutboxEventWriter outboxEventWriter,
                                         @Value("${app.staff-attendance.regularisation-window-days:30}") int windowDays) {
        this.repository = repository;
        this.insertGuard = insertGuard;
        this.attendanceRepository = attendanceRepository;
        this.attendanceService = attendanceService;
        this.academic = academic;
        this.tenantContext = tenantContext;
        this.authorizationContext = authorizationContext;
        this.auditLogWriter = auditLogWriter;
        this.outboxEventWriter = outboxEventWriter;
        this.windowDays = windowDays;
    }

    public sealed interface Result {
        record Success(FacultyRegularisation request) implements Result {
        }

        record NotAFaculty() implements Result {
        }

        record ProfileNotActive(String status) implements Result {
        }

        record InvalidRequest(String message) implements Result {
        }

        /** An open request already exists for that day; it is returned. */
        record AlreadyPending(FacultyRegularisation existing) implements Result {
        }

        /** The day already says exactly what was asked for. */
        record NothingToChange() implements Result {
        }

        record NotFound() implements Result {
        }

        /** Already decided or withdrawn. */
        record NotPending(RegularisationStatus current) implements Result {
        }

        /** An administrator tried to decide their own request. */
        record OwnRequest() implements Result {
        }
    }

    // ---- faculty: request / list / withdraw -------------------------------------------------------------------

    /**
     * Ask for a past day to be corrected. {@code checkIn}/{@code checkOut} are wall-clock times on that day in the college's
     * timezone (optional; only for PRESENT/HALF_DAY).
     */
    @Transactional
    public Result request(LocalDate date, FacultyAttendanceStatus status, LocalTime checkIn, LocalTime checkOut, String reason) {
        Optional<UUID> tenantId = tenantContext.tenantId();
        if (tenantId.isEmpty()) {
            return new Result.NotAFaculty();
        }
        UUID userId = authorizationContext.currentUserId();
        Optional<UUID> facultyId = academic.facultyIdOfUser(tenantId.get(), userId);
        if (facultyId.isEmpty()) {
            return new Result.NotAFaculty();
        }
        authorizationContext.assertCan(SELF_PERMISSION, ResourceRef.of(RESOURCE, facultyId.get(), tenantId.get()).ownedBy(userId));

        Optional<String> inactive = academic.facultyStatus(facultyId.get())
            .filter(st -> !"ACTIVE".equals(st));
        if (inactive.isPresent()) {
            return new Result.ProfileNotActive(inactive.get());
        }

        ZoneId zone = academic.timeZoneOf(tenantId.get());
        Instant now = Instant.now();
        LocalDate today = now.atZone(zone).toLocalDate();
        if (date == null || status == null) {
            return new Result.InvalidRequest("date and status are required");
        }
        if (!date.isBefore(today)) {
            return new Result.InvalidRequest("only a past day can be regularised; use check-in for today");
        }
        if (date.isBefore(today.minusDays(windowDays))) {
            return new Result.InvalidRequest("requests are accepted only for the last " + windowDays + " days");
        }
        if (status == FacultyAttendanceStatus.ABSENT) {
            return new Result.InvalidRequest("a request can ask for PRESENT, HALF_DAY or ON_LEAVE");
        }
        if (reason == null || reason.isBlank()) {
            return new Result.InvalidRequest("a reason is required");
        }
        if (reason.trim().length() > MAX_REASON_LENGTH) {
            return new Result.InvalidRequest("the reason must be at most " + MAX_REASON_LENGTH + " characters");
        }
        if (!status.hasWorkTimes() && (checkIn != null || checkOut != null)) {
            return new Result.InvalidRequest("check-in/check-out times only apply to PRESENT or HALF_DAY");
        }
        if (checkOut != null && checkIn == null) {
            return new Result.InvalidRequest("a check-out time needs a check-in time");
        }
        if (checkIn != null && checkOut != null && !checkOut.isAfter(checkIn)) {
            return new Result.InvalidRequest("check-out must be after check-in");
        }
        Instant inAt = checkIn == null ? null : date.atTime(checkIn).atZone(zone).toInstant();
        Instant outAt = checkOut == null ? null : date.atTime(checkOut).atZone(zone).toInstant();

        Optional<FacultyAttendance> existing = attendanceRepository.findByFacultyIdAndAttendanceDate(facultyId.get(), date);
        if (existing.isPresent() && alreadyMatches(existing.get(), status, inAt, outAt)) {
            return new Result.NothingToChange();
        }
        Optional<FacultyRegularisation> pending =
            repository.findByFacultyIdAndAttendanceDateAndStatus(facultyId.get(), date, RegularisationStatus.PENDING);
        if (pending.isPresent()) {
            return new Result.AlreadyPending(pending.get());
        }

        FacultyRegularisation row = new FacultyRegularisation(tenantId.get(), facultyId.get(), date, status, inAt, outAt, reason, now);
        try {
            insertGuard.insert(row, () -> {
                auditLogWriter.write(tenantId.get(), userId, "FACULTY_REGULARISATION_REQUESTED",
                    "attendance.faculty_attendance_regularisations", row.id(), details(row, null));
                emit(FacultyRegularisationEvent.REQUESTED, row, userId, row.reason());
            });
        } catch (DataIntegrityViolationException raced) {
            return new Result.AlreadyPending(repository
                .findByFacultyIdAndAttendanceDateAndStatus(facultyId.get(), date, RegularisationStatus.PENDING).orElseThrow(() -> raced));
        }
        return new Result.Success(row);
    }

    /** The caller's own requests, newest first (at most 100). */
    @Transactional(readOnly = true)
    public List<FacultyRegularisation> myRequests() {
        Optional<UUID> tenantId = tenantContext.tenantId();
        if (tenantId.isEmpty()) {
            return List.of();
        }
        UUID userId = authorizationContext.currentUserId();
        Optional<UUID> facultyId = academic.facultyIdOfUser(tenantId.get(), userId);
        if (facultyId.isEmpty()) {
            return List.of();
        }
        authorizationContext.assertCan(SELF_PERMISSION, ResourceRef.of(RESOURCE, facultyId.get(), tenantId.get()).ownedBy(userId));
        return repository.findTop100ByFacultyIdOrderByCreatedAtDesc(facultyId.get());
    }

    /** Withdraw the caller's OWN pending request. Anyone else's id looks exactly like a missing one. */
    @Transactional
    public Result withdraw(UUID requestId) {
        Optional<FacultyRegularisation> found = repository.findById(requestId);
        if (found.isEmpty()) {
            return new Result.NotFound();
        }
        FacultyRegularisation row = found.get();
        UUID userId = authorizationContext.currentUserId();
        Optional<UUID> ownFaculty = academic.facultyIdOfUser(row.tenantId(), userId);
        if (ownFaculty.isEmpty() || !ownFaculty.get().equals(row.facultyId())) {
            return new Result.NotFound();
        }
        authorizationContext.assertCan(SELF_PERMISSION, ResourceRef.of(RESOURCE, row.facultyId(), row.tenantId()).ownedBy(userId));
        if (!row.isPending()) {
            return new Result.NotPending(row.status());
        }
        row.withdraw(Instant.now());
        repository.save(row);
        auditLogWriter.write(row.tenantId(), userId, "FACULTY_REGULARISATION_WITHDRAWN",
            "attendance.faculty_attendance_regularisations", row.id(), details(row, null));
        emit(FacultyRegularisationEvent.WITHDRAWN, row, userId, null);
        return new Result.Success(row);
    }

    // ---- administrator: queue / decide ------------------------------------------------------------------------

    /** The college's requests, optionally only one status (the admin queue passes PENDING, oldest first). */
    @Transactional(readOnly = true)
    public List<FacultyRegularisation> listForTenant(UUID tenantId, RegularisationStatus statusOrNull) {
        authorizationContext.assertCan(MANAGE_PERMISSION, ResourceRef.of(RESOURCE, UUID.randomUUID(), tenantId));
        return statusOrNull == null
            ? repository.findTop200ByTenantIdOrderByCreatedAtDesc(tenantId)
            : repository.findTop200ByTenantIdAndStatusOrderByCreatedAtAsc(tenantId, statusOrNull);
    }

    /** Approve: applies the correction to the day, in this same transaction. The optional note is kept on the request. */
    @Transactional
    public Result approve(UUID requestId, String note) {
        Optional<FacultyRegularisation> found = repository.findById(requestId);
        if (found.isEmpty()) {
            return new Result.NotFound();
        }
        FacultyRegularisation row = found.get();
        authorizationContext.assertCan(MANAGE_PERMISSION, ResourceRef.of(RESOURCE, UUID.randomUUID(), row.tenantId()));
        UUID approver = authorizationContext.currentUserId();
        if (isOwnRequest(row, approver)) {
            return new Result.OwnRequest();
        }
        if (!row.isPending()) {
            return new Result.NotPending(row.status());
        }
        String cleanNote = note == null || note.isBlank() ? null : note.trim();
        String attendanceReason = "Regularisation approved. Faculty reason: " + row.reason()
            + (cleanNote == null ? "" : " | Administrator note: " + cleanNote);
        attendanceService.applyRegularisation(row, approver, attendanceReason);
        row.approve(approver, cleanNote, Instant.now());
        repository.save(row);
        auditLogWriter.write(row.tenantId(), approver, "FACULTY_REGULARISATION_APPROVED",
            "attendance.faculty_attendance_regularisations", row.id(), details(row, cleanNote));
        emit(FacultyRegularisationEvent.APPROVED, row, approver, cleanNote);
        return new Result.Success(row);
    }

    /** Reject: a note explaining why is required. Leaves the attendance day untouched. */
    @Transactional
    public Result reject(UUID requestId, String note) {
        Optional<FacultyRegularisation> found = repository.findById(requestId);
        if (found.isEmpty()) {
            return new Result.NotFound();
        }
        FacultyRegularisation row = found.get();
        authorizationContext.assertCan(MANAGE_PERMISSION, ResourceRef.of(RESOURCE, UUID.randomUUID(), row.tenantId()));
        UUID approver = authorizationContext.currentUserId();
        if (isOwnRequest(row, approver)) {
            return new Result.OwnRequest();
        }
        if (note == null || note.isBlank()) {
            return new Result.InvalidRequest("a note explaining the rejection is required");
        }
        if (!row.isPending()) {
            return new Result.NotPending(row.status());
        }
        row.reject(approver, note, Instant.now());
        repository.save(row);
        auditLogWriter.write(row.tenantId(), approver, "FACULTY_REGULARISATION_REJECTED",
            "attendance.faculty_attendance_regularisations", row.id(), details(row, row.decisionNote()));
        emit(FacultyRegularisationEvent.REJECTED, row, approver, row.decisionNote());
        return new Result.Success(row);
    }

    // ---- helpers ----------------------------------------------------------------------------------------------

    /** Nobody decides their own request: the approver's own faculty profile (if any) must not be the requester. */
    private boolean isOwnRequest(FacultyRegularisation row, UUID approverUserId) {
        return academic.facultyIdOfUser(row.tenantId(), approverUserId)
            .map(row.facultyId()::equals).orElse(false);
    }

    /** True when the day is already exactly what the request asks for, so approving it would change nothing. */
    private static boolean alreadyMatches(FacultyAttendance day, FacultyAttendanceStatus status, Instant in, Instant out) {
        if (day.status() != status) {
            return false;
        }
        if (!status.hasWorkTimes()) {
            return true;
        }
        boolean inOk = in == null ? day.checkInAt() != null : in.equals(day.checkInAt());
        boolean outOk = out == null ? day.checkOutAt() != null : out.equals(day.checkOutAt());
        return inOk && outOk;
    }

    private Map<String, Object> details(FacultyRegularisation row, String note) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("facultyId", row.facultyId());
        d.put("date", row.attendanceDate().toString());
        d.put("requestedStatus", row.requestedStatus().name());
        d.put("status", row.status().name());
        d.put("reason", row.reason());
        if (note != null) {
            d.put("note", note);
        }
        return d;
    }

    private void emit(String eventType, FacultyRegularisation row, UUID actor, String reasonOrNote) {
        UUID correlationId = UUID.randomUUID();
        var event = new FacultyRegularisationEvent(UUID.randomUUID(), eventType, Instant.now(), row.tenantId(), row.facultyId(),
            correlationId, null, row.id(), row.facultyId(), row.attendanceDate().toString(), row.requestedStatus().name(),
            row.status().name(), actor, reasonOrNote);
        outboxEventWriter.write(event.aggregateType(), row.facultyId(), event.eventType(), event.eventVersion(), event,
            row.tenantId(), correlationId, null);
    }
}
