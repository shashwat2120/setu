package com.setu.attendance.application;

import com.setu.attendance.domain.FacultyAttendance;
import com.setu.attendance.domain.FacultyAttendanceEvent;
import com.setu.attendance.domain.FacultyRegularisation;
import com.setu.attendance.domain.PolicyRules;
import com.setu.attendance.domain.FacultyAttendanceStatus;
import com.setu.attendance.infrastructure.FacultyAttendanceInsertGuard;
import com.setu.attendance.infrastructure.FacultyAttendanceRepository;
import com.setu.shared.academic.AcademicDirectory;
import com.setu.shared.authorization.AuthorizationContext;
import com.setu.shared.authorization.ResourceRef;
import com.setu.shared.governance.AuditLogWriter;
import com.setu.shared.governance.OutboxEventWriter;
import com.setu.shared.tenant.TenantContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * A faculty member's OWN daily attendance (staff check-in / check-out), separate from the per-class student
 * attendance in {@link AttendanceRecordService}. Rules: docs/state-machines.md #25.
 * <ul>
 *   <li>A faculty member may check in/out only for themself, only for TODAY (the day in the college's own timezone),
 *       and only while their profile is ACTIVE. They can read their own history, never anyone else's.</li>
 *   <li>Only an administrator ({@code MANAGE_STAFF_ATTENDANCE}) can record or correct any other day or any other
 *       faculty member, always with a reason; every change is audited with the old and new status.</li>
 *   <li>Tenant and identity always come from the authenticated principal, never from the request.</li>
 * </ul>
 * There is no shift schedule anywhere in this system, so "late" is not computed.
 */
@org.springframework.modulith.NamedInterface // shared.seed.DemoDataSeeder records example staff attendance through this
@Service
public class FacultyAttendanceService {

    private static final String SELF_PERMISSION = "CHECK_IN_STAFF_ATTENDANCE";
    private static final String MANAGE_PERMISSION = "MANAGE_STAFF_ATTENDANCE";
    private static final String RESOURCE = "staff_attendance";
    private static final long MAX_RANGE_DAYS = 366;

    private final FacultyAttendanceRepository repository;
    private final FacultyAttendanceInsertGuard insertGuard;
    private final AcademicDirectory academic;
    private final TenantContext tenantContext;
    private final AuthorizationContext authorizationContext;
    private final AuditLogWriter auditLogWriter;
    private final OutboxEventWriter outboxEventWriter;
    private final StaffAttendancePolicyService policyService;
    private final StaffLocationPolicyService locationPolicyService;

    public FacultyAttendanceService(FacultyAttendanceRepository repository, FacultyAttendanceInsertGuard insertGuard,
                                     AcademicDirectory academic,
                                     TenantContext tenantContext, AuthorizationContext authorizationContext,
                                     AuditLogWriter auditLogWriter, OutboxEventWriter outboxEventWriter,
                                     StaffAttendancePolicyService policyService, StaffLocationPolicyService locationPolicyService) {
        this.locationPolicyService = locationPolicyService;
        this.outboxEventWriter = outboxEventWriter;
        this.policyService = policyService;
        this.repository = repository;
        this.insertGuard = insertGuard;
        this.academic = academic;
        this.tenantContext = tenantContext;
        this.authorizationContext = authorizationContext;
        this.auditLogWriter = auditLogWriter;
    }

    // ---- results ----------------------------------------------------------------------------------------------

    public sealed interface RecordResult {
        record Success(FacultyAttendance record) implements RecordResult {
        }

        /** The caller is not a faculty member in their tenant (or has no tenant). */
        record NotAFaculty() implements RecordResult {
        }

        /** The faculty profile is not ACTIVE (on leave, suspended, terminated). */
        record ProfileNotActive(String status) implements RecordResult {
        }

        /** Today already has a record (possibly one an administrator made); the existing row is returned. */
        record AlreadyRecorded(FacultyAttendance record) implements RecordResult {
        }

        record NotCheckedIn() implements RecordResult {
        }

        record AlreadyCheckedOut(FacultyAttendance record) implements RecordResult {
        }

        record FacultyNotFound() implements RecordResult {
        }

        record InvalidRequest(String message) implements RecordResult {
        }
    }

    // ---- faculty: own day -------------------------------------------------------------------------------------

    /** Check in for today. First of the day wins; a second attempt (or a record an admin already made) is reported, not overwritten. */
    @Transactional
    public RecordResult checkIn() {
        return checkInFrom(null);
    }

    /**
     * Check in, optionally reporting where the device is. The position is used ONLY when the college has switched staff location on, and then only
     * its outcome against the campus is stored (never the coordinates); a college that has not switched it on has the position ignored, so nothing
     * about it is ever recorded. A check-in is never refused or altered because of where the device said it was.
     */
    @Transactional
    public RecordResult checkInFrom(AttendanceRecordService.LocationReport location) {
        Optional<UUID> tenantId = tenantContext.tenantId();
        if (tenantId.isEmpty()) {
            return new RecordResult.NotAFaculty();
        }
        UUID userId = authorizationContext.currentUserId();
        Optional<UUID> facultyId = academic.facultyIdOfUser(tenantId.get(), userId);
        if (facultyId.isEmpty()) {
            return new RecordResult.NotAFaculty();
        }
        assertSelf(tenantId.get(), facultyId.get(), userId);

        Optional<String> inactive = inactiveStatus(facultyId.get());
        if (inactive.isPresent()) {
            return new RecordResult.ProfileNotActive(inactive.get());
        }

        Instant now = Instant.now();
        LocalDate today = today(tenantId.get(), now);
        Optional<FacultyAttendance> existing = repository.findByFacultyIdAndAttendanceDate(facultyId.get(), today);
        if (existing.isPresent()) {
            return new RecordResult.AlreadyRecorded(existing.get());
        }
        FacultyAttendance row = FacultyAttendance.checkIn(tenantId.get(), facultyId.get(), today, now);
        row.applyPolicy(policyService.currentRules(tenantId.get()), academic.timeZoneOf(tenantId.get()));
        var campus = locationPolicyService.settingsFor(tenantId.get());
        if (campus.enabled()) {
            var outcome = location == null
                ? new CheckInGeo.Outcome(com.setu.attendance.domain.CheckInLocation.NO_LOCATION, null, null)
                : CheckInGeo.assess(campus.campusLatitude(), campus.campusLongitude(), location.latitude(), location.longitude(),
                    location.accuracyMeters(), campus.radiusMeters());
            row.recordCheckInLocation(outcome.status(), outcome.distanceMeters(), outcome.accuracyMeters());
        }
        try {
            insertGuard.insert(row, () -> {
                auditLogWriter.write(tenantId.get(), userId, "FACULTY_CHECKED_IN", "attendance.faculty_attendance", row.id(),
                    Map.of("facultyId", facultyId.get(), "date", today.toString()));
                emit(FacultyAttendanceEvent.CHECKED_IN, row, null, userId, null);
            });
        } catch (DataIntegrityViolationException raced) {
            return new RecordResult.AlreadyRecorded(
                repository.findByFacultyIdAndAttendanceDate(facultyId.get(), today).orElseThrow(() -> raced));
        }
        return new RecordResult.Success(row);
    }

    /** Check out of today's open check-in; once only, never before the check-in. */
    @Transactional
    public RecordResult checkOut() {
        Optional<UUID> tenantId = tenantContext.tenantId();
        if (tenantId.isEmpty()) {
            return new RecordResult.NotAFaculty();
        }
        UUID userId = authorizationContext.currentUserId();
        Optional<UUID> facultyId = academic.facultyIdOfUser(tenantId.get(), userId);
        if (facultyId.isEmpty()) {
            return new RecordResult.NotAFaculty();
        }
        assertSelf(tenantId.get(), facultyId.get(), userId);

        Instant now = Instant.now();
        LocalDate today = today(tenantId.get(), now);
        Optional<FacultyAttendance> existing = repository.findByFacultyIdAndAttendanceDate(facultyId.get(), today);
        if (existing.isEmpty() || !existing.get().isCheckedIn()) {
            return new RecordResult.NotCheckedIn();
        }
        FacultyAttendance row = existing.get();
        if (row.isCheckedOut()) {
            return new RecordResult.AlreadyCheckedOut(row);
        }
        row.checkOut(now);
        PolicyRules rules = policyService.currentRules(tenantId.get());
        row.applyPolicy(rules, academic.timeZoneOf(tenantId.get()));
        row.applySelfCheckOutOutcome(rules);
        repository.save(row);
        Map<String, Object> outDetails = new LinkedHashMap<>();
        outDetails.put("facultyId", facultyId.get());
        outDetails.put("date", today.toString());
        outDetails.put("status", row.status().name());
        outDetails.put("workedMinutes", row.workedMinutes());
        outDetails.put("shortHours", row.shortHours());
        outDetails.put("policyVersion", row.policyVersion());
        auditLogWriter.write(tenantId.get(), userId, "FACULTY_CHECKED_OUT", "attendance.faculty_attendance", row.id(), outDetails);
        emit(FacultyAttendanceEvent.CHECKED_OUT, row, null, userId, null);
        return new RecordResult.Success(row);
    }

    /** Today's record for the caller, if any. */
    @Transactional(readOnly = true)
    public Optional<FacultyAttendance> myToday() {
        Optional<UUID> tenantId = tenantContext.tenantId();
        if (tenantId.isEmpty()) {
            return Optional.empty();
        }
        UUID userId = authorizationContext.currentUserId();
        Optional<UUID> facultyId = academic.facultyIdOfUser(tenantId.get(), userId);
        if (facultyId.isEmpty()) {
            return Optional.empty();
        }
        assertSelf(tenantId.get(), facultyId.get(), userId);
        return repository.findByFacultyIdAndAttendanceDate(facultyId.get(), today(tenantId.get(), Instant.now()));
    }

    /** Server-side "today" in the caller's college timezone, so the UI never has to guess the date. */
    @Transactional(readOnly = true)
    public Optional<LocalDate> todayForCurrentTenant() {
        return tenantContext.tenantId().map(t -> today(t, Instant.now()));
    }

    // ---- read: own history, or any faculty for an admin -------------------------------------------------------

    /** The faculty member themself, or an administrator with MANAGE_STAFF_ATTENDANCE for that faculty's college. Range is capped. */
    @Transactional(readOnly = true)
    public Optional<List<FacultyAttendance>> history(UUID facultyId, LocalDate from, LocalDate to) {
        Optional<UUID> tenantId = academic.facultyTenant(facultyId);
        if (tenantId.isEmpty()) {
            return Optional.empty();
        }
        assertSelfOrManage(tenantId.get(), facultyId);
        validateRange(from, to);
        return Optional.of(repository.findByFacultyIdAndAttendanceDateBetweenOrderByAttendanceDateDesc(facultyId, from, to));
    }

    /** Every faculty record in a college for one day — admin only. */
    @Transactional(readOnly = true)
    public List<FacultyAttendance> listForDate(UUID tenantId, LocalDate date) {
        authorizationContext.assertCan(MANAGE_PERMISSION, ResourceRef.of(RESOURCE, UUID.randomUUID(), tenantId));
        return repository.findByTenantIdAndAttendanceDate(tenantId, date);
    }

    // ---- admin: mark / correct --------------------------------------------------------------------------------

    /** Create or correct one faculty member's record for a past or current day. Requires a reason; never a future day. */
    @Transactional
    public RecordResult markByAdmin(UUID facultyId, LocalDate date, FacultyAttendanceStatus status, String reason) {
        Optional<UUID> tenantId = academic.facultyTenant(facultyId);
        if (tenantId.isEmpty()) {
            return new RecordResult.FacultyNotFound();
        }
        authorizationContext.assertCan(MANAGE_PERMISSION, ResourceRef.of(RESOURCE, UUID.randomUUID(), tenantId.get()));
        if (date == null || status == null) {
            return new RecordResult.InvalidRequest("date and status are required");
        }
        if (reason == null || reason.isBlank()) {
            return new RecordResult.InvalidRequest("a reason is required");
        }
        Instant now = Instant.now();
        if (date.isAfter(today(tenantId.get(), now))) {
            return new RecordResult.InvalidRequest("attendance cannot be recorded for a future date");
        }

        UUID adminId = authorizationContext.currentUserId();
        Optional<FacultyAttendance> existing = repository.findByFacultyIdAndAttendanceDate(facultyId, date);
        if (existing.isPresent()) {
            FacultyAttendance row = existing.get();
            FacultyAttendanceStatus before = row.status();
            row.correctByAdmin(status, adminId, reason, now);
            refreshMetrics(row);
            repository.save(row);
            audit(tenantId.get(), adminId, row, "FACULTY_ATTENDANCE_CORRECTED", before, reason);
            emit(FacultyAttendanceEvent.CORRECTED, row, before, adminId, reason);
            return new RecordResult.Success(row);
        }
        FacultyAttendance row = FacultyAttendance.markedByAdmin(tenantId.get(), facultyId, date, status, adminId, reason, now);
        try {
            insertGuard.insert(row, () -> {
                audit(tenantId.get(), adminId, row, "FACULTY_ATTENDANCE_MARKED", null, reason);
                emit(FacultyAttendanceEvent.MARKED, row, null, adminId, reason);
            });
        } catch (DataIntegrityViolationException raced) {
            // someone (the faculty member checking in, or another admin) created this day a moment ago; apply the mark to that row
            FacultyAttendance winner = repository.findByFacultyIdAndAttendanceDate(facultyId, date).orElseThrow(() -> raced);
            FacultyAttendanceStatus before = winner.status();
            winner.correctByAdmin(status, adminId, reason, now);
            refreshMetrics(winner);
            repository.save(winner);
            audit(tenantId.get(), adminId, winner, "FACULTY_ATTENDANCE_CORRECTED", before, reason);
            emit(FacultyAttendanceEvent.CORRECTED, winner, before, adminId, reason);
            return new RecordResult.Success(winner);
        }
        return new RecordResult.Success(row);
    }

    /**
     * Applies an APPROVED regularisation to the day it asked about — called by {@link FacultyRegularisationService} inside ITS
     * transaction, so the attendance change, the decision, the audit rows and the events commit or roll back together (hence a
     * plain save here, not the REQUIRES_NEW insert guard). The caller has already authorized the approver. A clash with a row
     * created concurrently surfaces as a constraint violation and rolls the whole approval back for a retry.
     */
    FacultyAttendance applyRegularisation(FacultyRegularisation request, UUID approverUserId, String reason) {
        Instant now = Instant.now();
        Optional<FacultyAttendance> existing = repository.findByFacultyIdAndAttendanceDate(request.facultyId(), request.attendanceDate());
        if (existing.isPresent()) {
            FacultyAttendance row = existing.get();
            FacultyAttendanceStatus before = row.status();
            row.regularise(request.requestedStatus(), request.requestedCheckInAt(), request.requestedCheckOutAt(), approverUserId, reason, now);
            refreshMetrics(row);
            repository.save(row);
            audit(request.tenantId(), approverUserId, row, "FACULTY_ATTENDANCE_CORRECTED", before, reason);
            emit(FacultyAttendanceEvent.CORRECTED, row, before, approverUserId, reason);
            return row;
        }
        FacultyAttendance row = FacultyAttendance.regularised(request.tenantId(), request.facultyId(), request.attendanceDate(),
            request.requestedStatus(), request.requestedCheckInAt(), request.requestedCheckOutAt(), approverUserId, reason, now);
        refreshMetrics(row);
        repository.saveAndFlush(row);
        audit(request.tenantId(), approverUserId, row, "FACULTY_ATTENDANCE_MARKED", null, reason);
        emit(FacultyAttendanceEvent.MARKED, row, null, approverUserId, reason);
        return row;
    }

    /** The caller's college clock for the live widget: the server's current instant and the college timezone (the browser's own clock is never trusted). */
    @Transactional(readOnly = true)
    public Optional<java.time.ZoneId> zoneForCurrentTenant() {
        return tenantContext.tenantId().map(academic::timeZoneOf);
    }

    // ---- helpers ----------------------------------------------------------------------------------------------

    /** Re-judges a day's times under the college's CURRENT policy after an administrator changed it (status is left as the administrator set it). */
    private void refreshMetrics(FacultyAttendance row) {
        row.applyPolicy(policyService.currentRules(row.tenantId()), academic.timeZoneOf(row.tenantId()));
    }

    private void audit(UUID tenantId, UUID actor, FacultyAttendance row, String action, FacultyAttendanceStatus before, String reason) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("facultyId", row.facultyId());
        details.put("date", row.attendanceDate().toString());
        if (before != null) {
            details.put("from", before.name());
        }
        details.put("to", row.status().name());
        details.put("reason", reason.trim());
        auditLogWriter.write(tenantId, actor, action, "attendance.faculty_attendance", row.id(), details);
    }

    /**
     * Transactional outbox (same transaction as the change, published to {@code setu.faculty_attendance} by
     * OutboxPublisher). Ordering key is the faculty member, so one person's events stay in order.
     */
    private void emit(String eventType, FacultyAttendance row, FacultyAttendanceStatus previous, UUID actor, String reason) {
        UUID correlationId = UUID.randomUUID();
        var event = new FacultyAttendanceEvent(UUID.randomUUID(), eventType, Instant.now(), row.tenantId(), row.facultyId(),
            correlationId, null, row.id(), row.facultyId(), row.attendanceDate().toString(), row.status().name(),
            previous == null ? null : previous.name(), row.checkInAt(), row.checkOutAt(), actor,
            reason == null ? null : reason.trim(), row.lateMinutes(), row.workedMinutes(), row.policyVersion());
        outboxEventWriter.write(event.aggregateType(), row.facultyId(), event.eventType(), event.eventVersion(), event,
            row.tenantId(), correlationId, null);
    }

    /** Own-record authorization: the permission, plus ownership of THIS faculty profile by the caller. */
    private void assertSelf(UUID tenantId, UUID facultyId, UUID userId) {
        authorizationContext.assertCan(SELF_PERMISSION, ResourceRef.of(RESOURCE, facultyId, tenantId).ownedBy(userId));
    }

    private void assertSelfOrManage(UUID tenantId, UUID facultyId) {
        UUID userId = authorizationContext.currentUserId();
        boolean isSelf = academic.facultyIdOfUser(tenantId, userId)
            .map(facultyId::equals).orElse(false);
        if (isSelf) {
            assertSelf(tenantId, facultyId, userId);
            return;
        }
        authorizationContext.assertCan(MANAGE_PERMISSION, ResourceRef.of(RESOURCE, UUID.randomUUID(), tenantId));
    }

    private Optional<String> inactiveStatus(UUID facultyId) {
        return academic.facultyStatus(facultyId)
            .filter(status -> !"ACTIVE".equals(status));
    }

    private LocalDate today(UUID tenantId, Instant now) {
        return now.atZone(academic.timeZoneOf(tenantId)).toLocalDate();
    }

    private static void validateRange(LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from)) {
            throw new IllegalArgumentException("from and to are required and to must not be before from");
        }
        if (java.time.temporal.ChronoUnit.DAYS.between(from, to) > MAX_RANGE_DAYS) {
            throw new IllegalArgumentException("date range is limited to " + MAX_RANGE_DAYS + " days");
        }
    }
}
