package com.setu.attendance.application;

import com.setu.attendance.domain.PolicyRules;
import com.setu.attendance.domain.StaffAttendancePolicy;
import com.setu.attendance.domain.StaffAttendancePolicyChangedEvent;
import com.setu.attendance.infrastructure.StaffAttendancePolicyInsertGuard;
import com.setu.attendance.infrastructure.StaffAttendancePolicyRepository;
import com.setu.shared.authorization.AuthorizationContext;
import com.setu.shared.authorization.ResourceRef;
import com.setu.shared.governance.AuditLogWriter;
import com.setu.shared.governance.OutboxEventWriter;
import com.setu.shared.tenant.TenantContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * A college's staff-attendance rules: shift, grace period, and the hours that make a full or half day. Versioned and append-only —
 * saving changes inserts the next version, so every attendance record keeps the version it was judged under and past days are never
 * silently re-judged. A college with no saved policy gets {@link PolicyRules#defaults()} (version 0, never stored).
 * <p>
 * Anyone in the college who can use staff attendance (faculty: own check-in permission; administrators: manage permission) can READ
 * the rules; only an administrator ({@code MANAGE_STAFF_ATTENDANCE}) can change them. The college always comes from the authenticated
 * principal, never from the request.
 */
@Service
public class StaffAttendancePolicyService {

    private static final String SELF_PERMISSION = "CHECK_IN_STAFF_ATTENDANCE";
    private static final String MANAGE_PERMISSION = "MANAGE_STAFF_ATTENDANCE";
    private static final String RESOURCE = "staff_attendance";

    private final StaffAttendancePolicyRepository repository;
    private final StaffAttendancePolicyInsertGuard insertGuard;
    private final TenantContext tenantContext;
    private final AuthorizationContext authorizationContext;
    private final AuditLogWriter auditLogWriter;
    private final OutboxEventWriter outboxEventWriter;

    public StaffAttendancePolicyService(StaffAttendancePolicyRepository repository, StaffAttendancePolicyInsertGuard insertGuard,
                                         TenantContext tenantContext, AuthorizationContext authorizationContext,
                                         AuditLogWriter auditLogWriter, OutboxEventWriter outboxEventWriter) {
        this.repository = repository;
        this.insertGuard = insertGuard;
        this.tenantContext = tenantContext;
        this.authorizationContext = authorizationContext;
        this.auditLogWriter = auditLogWriter;
        this.outboxEventWriter = outboxEventWriter;
    }

    public sealed interface UpdateResult {
        record Success(PolicyRules rules) implements UpdateResult {
        }

        record InvalidRequest(String message) implements UpdateResult {
        }

        /** The submitted values equal the current policy. */
        record NoChange(PolicyRules current) implements UpdateResult {
        }

        /** Another administrator saved the next version first; reload and try again. */
        record Conflict() implements UpdateResult {
        }

        record NoCollege() implements UpdateResult {
        }
    }

    /** The rules in force for a college, with NO authorization check — for callers that have already authorized (check-in, regularisation). */
    @Transactional(readOnly = true)
    public PolicyRules currentRules(UUID tenantId) {
        return repository.findFirstByTenantIdOrderByVersionDesc(tenantId).map(StaffAttendancePolicy::rules).orElseGet(PolicyRules::defaults);
    }

    /** The caller's own college's rules — readable by faculty (own permission) and administrators. */
    @Transactional(readOnly = true)
    public Optional<PolicyRules> currentForCaller() {
        Optional<UUID> tenantId = tenantContext.tenantId();
        if (tenantId.isEmpty()) {
            return Optional.empty();
        }
        ResourceRef resource = ResourceRef.of(RESOURCE, UUID.randomUUID(), tenantId.get());
        if (!authorizationContext.can(MANAGE_PERMISSION, resource).isAllowed()) {
            authorizationContext.assertCan(SELF_PERMISSION, resource);
        }
        return Optional.of(currentRules(tenantId.get()));
    }

    /** Save a new policy version for the caller's college. Administrators only. */
    @Transactional
    public UpdateResult update(LocalTime shiftStart, LocalTime shiftEnd, int graceMinutes, int fullDayMinutes, int halfDayMinutes) {
        Optional<UUID> tenantId = tenantContext.tenantId();
        if (tenantId.isEmpty()) {
            return new UpdateResult.NoCollege();
        }
        authorizationContext.assertCan(MANAGE_PERMISSION, ResourceRef.of(RESOURCE, UUID.randomUUID(), tenantId.get()));

        PolicyRules current = currentRules(tenantId.get());
        PolicyRules next;
        try {
            next = new PolicyRules(current.version() + 1, shiftStart, shiftEnd, graceMinutes, fullDayMinutes, halfDayMinutes);
        } catch (IllegalArgumentException e) {
            return new UpdateResult.InvalidRequest(e.getMessage());
        }
        if (sameRules(current, next)) {
            return new UpdateResult.NoChange(current);
        }

        UUID adminId = authorizationContext.currentUserId();
        Instant now = Instant.now();
        StaffAttendancePolicy row = new StaffAttendancePolicy(tenantId.get(), next, adminId, now);
        try {
            insertGuard.insert(row, () -> {
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("fromVersion", current.version());
                details.put("toVersion", next.version());
                details.put("from", describe(current));
                details.put("to", describe(next));
                auditLogWriter.write(tenantId.get(), adminId, "STAFF_ATTENDANCE_POLICY_CHANGED",
                    "attendance.staff_attendance_policies", row.id(), details);
                UUID correlationId = UUID.randomUUID();
                var event = new StaffAttendancePolicyChangedEvent(UUID.randomUUID(), now, tenantId.get(), tenantId.get(), correlationId,
                    null, next.version(), next.shiftStart().toString(), next.shiftEnd().toString(), next.graceMinutes(),
                    next.fullDayMinutes(), next.halfDayMinutes(), adminId);
                outboxEventWriter.write(event.aggregateType(), tenantId.get(), event.eventType(), event.eventVersion(), event,
                    tenantId.get(), correlationId, null);
            });
        } catch (DataIntegrityViolationException e) {
            if (isDuplicateVersion(e)) {
                return new UpdateResult.Conflict();
            }
            throw e; // any other constraint failure is a bug, not a race — never disguise it as a retryable conflict
        }
        return new UpdateResult.Success(next);
    }

    private static boolean isDuplicateVersion(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t.getMessage() != null && t.getMessage().contains("uq_staff_attendance_policies_tenant_version")) {
                return true;
            }
        }
        return false;
    }

    private static boolean sameRules(PolicyRules a, PolicyRules b) {
        return a.shiftStart().equals(b.shiftStart()) && a.shiftEnd().equals(b.shiftEnd()) && a.graceMinutes() == b.graceMinutes()
            && a.fullDayMinutes() == b.fullDayMinutes() && a.halfDayMinutes() == b.halfDayMinutes();
    }

    private static Map<String, Object> describe(PolicyRules r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("shiftStart", r.shiftStart().toString());
        m.put("shiftEnd", r.shiftEnd().toString());
        m.put("graceMinutes", r.graceMinutes());
        m.put("fullDayMinutes", r.fullDayMinutes());
        m.put("halfDayMinutes", r.halfDayMinutes());
        return m;
    }
}
