package com.setu.attendance.application;

import com.setu.attendance.domain.StaffLocationPolicy;
import com.setu.attendance.infrastructure.StaffLocationPolicyRepository;
import com.setu.shared.authorization.AuthorizationContext;
import com.setu.shared.authorization.ResourceRef;
import com.setu.shared.governance.AuditLogWriter;
import com.setu.shared.tenant.TenantContext;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Whether a college records where staff check in from, and the campus position it compares with. OFF until an administrator switches it on.
 * Faculty can READ whether it is on (so the check-in page can tell them before anything is asked of their device); only an administrator
 * ({@code MANAGE_STAFF_ATTENDANCE}) can change it, and only an administrator is shown the campus coordinates. The college always comes from the
 * authenticated principal, never from the request. Every change is audited with who made it.
 */
@Service
public class StaffLocationPolicyService {

    private static final String SELF_PERMISSION = "CHECK_IN_STAFF_ATTENDANCE";
    private static final String MANAGE_PERMISSION = "MANAGE_STAFF_ATTENDANCE";
    private static final String RESOURCE = "staff_attendance";

    private final StaffLocationPolicyRepository repository;
    private final TenantContext tenantContext;
    private final AuthorizationContext authorizationContext;
    private final AuditLogWriter auditLogWriter;

    public StaffLocationPolicyService(StaffLocationPolicyRepository repository, TenantContext tenantContext,
                                      AuthorizationContext authorizationContext, AuditLogWriter auditLogWriter) {
        this.repository = repository;
        this.tenantContext = tenantContext;
        this.authorizationContext = authorizationContext;
        this.auditLogWriter = auditLogWriter;
    }

    /** What a check-in needs: whether to record, and what to compare with. No authorization of its own: for callers that have authorized (check-in). */
    public record Settings(boolean enabled, Double campusLatitude, Double campusLongitude, int radiusMeters) {
        static Settings off() {
            return new Settings(false, null, null, StaffLocationPolicy.DEFAULT_RADIUS_METERS);
        }
    }

    /**
     * What a caller is shown. {@code campusLatitude}/{@code campusLongitude} are null for anyone but an administrator, who needs them to edit the policy.
     */
    public record View(boolean enabled, boolean campusConfigured, int radiusMeters, Double campusLatitude, Double campusLongitude) {
    }

    public sealed interface UpdateResult {
        record Success(View view) implements UpdateResult {
        }

        record InvalidRequest(String message) implements UpdateResult {
        }

        record Conflict() implements UpdateResult {
        }

        record NoCollege() implements UpdateResult {
        }
    }

    @Transactional(readOnly = true)
    public Settings settingsFor(UUID tenantId) {
        return repository.findById(tenantId)
            .map(p -> new Settings(p.enabled(), p.campusLatitude(), p.campusLongitude(), p.radiusMeters()))
            .orElseGet(Settings::off);
    }

    /** The caller's own college's setting: faculty (own check-in permission) and administrators can read it; only an administrator sees the coordinates. */
    @Transactional(readOnly = true)
    public Optional<View> currentForCaller() {
        Optional<UUID> tenantId = tenantContext.tenantId();
        if (tenantId.isEmpty()) {
            return Optional.empty();
        }
        ResourceRef resource = ResourceRef.of(RESOURCE, UUID.randomUUID(), tenantId.get());
        boolean admin = authorizationContext.can(MANAGE_PERMISSION, resource).isAllowed();
        if (!admin) {
            authorizationContext.assertCan(SELF_PERMISSION, resource);
        }
        return Optional.of(view(settingsFor(tenantId.get()), admin));
    }

    /** Administrators only. {@code enabled} needs a campus position; the radius is 50 to 5000 metres. */
    @Transactional
    public UpdateResult update(boolean enabled, Double latitude, Double longitude, Integer radiusMeters) {
        Optional<UUID> tenantId = tenantContext.tenantId();
        if (tenantId.isEmpty()) {
            return new UpdateResult.NoCollege();
        }
        authorizationContext.assertCan(MANAGE_PERMISSION, ResourceRef.of(RESOURCE, UUID.randomUUID(), tenantId.get()));

        if ((latitude == null) != (longitude == null)) {
            return new UpdateResult.InvalidRequest("give both latitude and longitude of the campus, or neither");
        }
        if (latitude != null && !CheckInGeo.isValidPosition(latitude, longitude)) {
            return new UpdateResult.InvalidRequest("the campus position is not a valid latitude and longitude");
        }
        if (enabled && latitude == null) {
            return new UpdateResult.InvalidRequest("the campus position is needed to switch location on");
        }
        int radius = radiusMeters == null ? StaffLocationPolicy.DEFAULT_RADIUS_METERS : radiusMeters;
        if (radius < StaffLocationPolicy.MIN_RADIUS_METERS || radius > StaffLocationPolicy.MAX_RADIUS_METERS) {
            return new UpdateResult.InvalidRequest("the radius must be between " + StaffLocationPolicy.MIN_RADIUS_METERS + " and "
                + StaffLocationPolicy.MAX_RADIUS_METERS + " metres");
        }

        UUID adminId = authorizationContext.currentUserId();
        Instant now = Instant.now();
        try {
            StaffLocationPolicy row = repository.findById(tenantId.get()).orElseGet(() -> new StaffLocationPolicy(tenantId.get()));
            boolean before = row.enabled();
            row.configure(enabled, latitude, longitude, radius, adminId, now);
            repository.saveAndFlush(row);
            auditLogWriter.write(tenantId.get(), adminId, "STAFF_LOCATION_POLICY_CHANGED", "attendance.staff_location_policies", tenantId.get(),
                Map.of("enabledBefore", before, "enabledAfter", enabled, "radiusMeters", radius, "campusPositionSet", latitude != null));
            return new UpdateResult.Success(view(new Settings(row.enabled(), row.campusLatitude(), row.campusLongitude(), row.radiusMeters()), true));
        } catch (ObjectOptimisticLockingFailureException e) {
            return new UpdateResult.Conflict();
        }
    }

    private static View view(Settings s, boolean includeCoordinates) {
        return new View(s.enabled(), s.campusLatitude() != null, s.radiusMeters(),
            includeCoordinates ? s.campusLatitude() : null, includeCoordinates ? s.campusLongitude() : null);
    }
}
