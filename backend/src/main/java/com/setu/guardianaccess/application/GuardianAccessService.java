package com.setu.guardianaccess.application;

import com.setu.directory.application.GuardianProfileService;
import com.setu.directory.application.StudentProfileService;
import com.setu.guardianaccess.domain.GuardianAccessGrant;
import com.setu.guardianaccess.domain.GuardianAccessPermission;
import com.setu.guardianaccess.infrastructure.GuardianAccessGrantRepository;
import com.setu.guardianaccess.infrastructure.GuardianAccessPermissionRepository;
import com.setu.shared.authorization.AuthorizationContext;
import com.setu.shared.authorization.ResourceRef;
import com.setu.shared.governance.AuditLogWriter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Guardian access module's public port — the authoritative implementation
 * of the Day 4 §5 / Day 6 §8 rule: a relationship alone
 * (directory.student_guardians, not touched here) never grants access;
 * access requires an active grant AND a specific permission on that grant
 * AND the right tenant AND the right target student. No Kafka event is
 * emitted for grant/revoke — no such event exists in the locked
 * docs/kafka-event-catalog.md; the audit_logs entry is what's actually
 * asked for.
 * <p>
 * {@code @NamedInterface} on this class only (Day 5 §13) — not the whole
 * {@code application} package.
 */
@org.springframework.modulith.NamedInterface
@Service
public class GuardianAccessService {

    private static final String MANAGE_PERMISSION = "MANAGE_GUARDIAN_ACCESS";

    /**
     * Day 6 §8: a grant can only ever confer a permission the project has
     * explicitly scoped to guardian delegation — never MANAGE_* or
     * platform/finance codes, even though the FK to identity.permissions.code
     * would technically allow any existing code. The original four (V9) cover
     * grades/attendance/schedule/academic-status; {@code VIEW_LEARNING_DATA}
     * was added Day 10, the first real caller of the guardian-delegation path
     * for learning_progress — see {@code LearningProgressService}'s javadoc.
     */
    private static final Set<String> SUPPORTED_GUARDIAN_PERMISSIONS =
        // VIEW_FINANCE added Day 12 — the authorization matrix's own note says it is "not in the default guardian
        // ceiling ... a tenant must explicitly add it" (matching contradiction-audit #6's policy), so it is
        // delegatable here but never auto-granted to the GUARDIAN role itself (see V67).
        // VIEW_RISK_SCORE added Day 13 — authorization-matrix.md's "Risk score / factors" row gives GUARDIAN
        // "Read own (if granted — narrow default)"; the row directly below it, "Recommendation / intervention",
        // gives GUARDIAN "—" (no access at all) and MANAGE_INTERVENTIONS is intentionally NOT added here.
        Set.of("VIEW_GRADES", "VIEW_ATTENDANCE", "VIEW_SCHEDULE", "VIEW_ACADEMIC_STATUS", "VIEW_LEARNING_DATA",
            "VIEW_FINANCE", "VIEW_RISK_SCORE");

    private final GuardianAccessGrantRepository grantRepository;
    private final GuardianAccessPermissionRepository permissionRepository;
    private final GuardianProfileService guardianProfileService;
    private final StudentProfileService studentProfileService;
    private final AuthorizationContext authorizationContext;
    private final AuditLogWriter auditLogWriter;

    public GuardianAccessService(GuardianAccessGrantRepository grantRepository,
                                  GuardianAccessPermissionRepository permissionRepository,
                                  GuardianProfileService guardianProfileService,
                                  StudentProfileService studentProfileService,
                                  AuthorizationContext authorizationContext, AuditLogWriter auditLogWriter) {
        this.grantRepository = grantRepository;
        this.permissionRepository = permissionRepository;
        this.guardianProfileService = guardianProfileService;
        this.studentProfileService = studentProfileService;
        this.authorizationContext = authorizationContext;
        this.auditLogWriter = auditLogWriter;
    }

    public sealed interface GrantResult {
        record Success(UUID grantId) implements GrantResult {
        }

        record StudentNotFound() implements GrantResult {
        }

        record GuardianNotFound() implements GrantResult {
        }

        record TenantMismatch() implements GrantResult {
        }

        /** Day 6 §8: "guardian cannot grant themselves access." */
        record GuardianCannotGrantSelf() implements GrantResult {
        }

        /** Day 6 §8: "permissions must be explicit and bounded." */
        record UnsupportedPermission(String code) implements GrantResult {
        }
    }

    /**
     * {@code grantedBy} is NEVER a caller-supplied parameter — it is always
     * the currently authenticated principal (Day 6 §8: "granted_by must be
     * an authorized principal"), read from {@link AuthorizationContext}
     * exactly like every other sensitive-operation actor in this codebase.
     */
    @Transactional
    public GrantResult grantAccess(UUID studentId, UUID guardianId, Instant expiresAt, Set<String> permissionCodes) {
        Optional<UUID> studentTenant = studentProfileService.findTenantId(studentId);
        if (studentTenant.isEmpty()) {
            return new GrantResult.StudentNotFound();
        }
        Optional<GuardianProfileService.GuardianSummary> guardian = guardianProfileService.getSummary(guardianId);
        if (guardian.isEmpty()) {
            return new GrantResult.GuardianNotFound();
        }
        if (!studentTenant.get().equals(guardian.get().tenantId())) {
            return new GrantResult.TenantMismatch();
        }
        UUID tenantId = studentTenant.get();
        authorizationContext.assertCan(MANAGE_PERMISSION, ResourceRef.of("guardian_access_grant", UUID.randomUUID(), tenantId));

        UUID actor = authorizationContext.currentUserId();
        if (guardian.get().ownerUserId() != null && guardian.get().ownerUserId().equals(actor)) {
            return new GrantResult.GuardianCannotGrantSelf();
        }
        for (String code : permissionCodes) {
            if (!SUPPORTED_GUARDIAN_PERMISSIONS.contains(code)) {
                return new GrantResult.UnsupportedPermission(code);
            }
        }

        Instant now = Instant.now();
        GuardianAccessGrant grant = new GuardianAccessGrant(UUID.randomUUID(), tenantId, studentId, guardianId,
            actor, expiresAt, now);
        grantRepository.save(grant);
        for (String code : permissionCodes) {
            permissionRepository.save(new GuardianAccessPermission(UUID.randomUUID(), grant.id(), code));
        }
        auditLogWriter.write(tenantId, actor, "GUARDIAN_ACCESS_GRANTED", "guardian_access_grants", grant.id(),
            Map.of("studentId", studentId, "guardianId", guardianId, "permissions", permissionCodes,
                "expiresAt", String.valueOf(expiresAt)));
        return new GrantResult.Success(grant.id());
    }

    public enum RevokeResult {SUCCESS, NOT_FOUND}

    @Transactional
    public RevokeResult revokeAccess(UUID grantId, String reason) {
        var maybeGrant = grantRepository.findById(grantId);
        if (maybeGrant.isEmpty()) {
            return RevokeResult.NOT_FOUND;
        }
        GuardianAccessGrant grant = maybeGrant.get();
        authorizationContext.assertCan(MANAGE_PERMISSION, ResourceRef.of("guardian_access_grant", grant.id(), grant.tenantId()));
        UUID actor = authorizationContext.currentUserId();
        grant.revoke(Instant.now());
        grantRepository.save(grant);
        auditLogWriter.write(grant.tenantId(), actor, "GUARDIAN_ACCESS_REVOKED", "guardian_access_grants",
            grant.id(), Map.of("studentId", grant.studentId(), "guardianId", grant.guardianId(),
                "reason", reason == null ? "" : reason));
        return RevokeResult.SUCCESS;
    }

    /**
     * The full Day 4 §5 / Day 6 §8 check in one call: active grant +
     * specific permission + tenant + target student, all at once — nothing
     * short of all four returns true. No authorization gate of its own —
     * this is called by AuthorizationService itself (via
     * GuardianDelegationAdapter) as PART OF an authorization decision, not
     * after one.
     */
    @Transactional(readOnly = true)
    public boolean hasActiveDelegatedAccess(UUID guardianId, UUID studentId, UUID tenantId, String permissionCode,
                                             Instant now) {
        List<GuardianAccessGrant> grants =
            grantRepository.findByGuardianIdAndStudentIdAndTenantId(guardianId, studentId, tenantId);
        for (GuardianAccessGrant grant : grants) {
            if (!grant.isUsable(now)) {
                continue;
            }
            boolean hasPermission = permissionRepository.findByGrantId(grant.id()).stream()
                .anyMatch(p -> p.permissionCode().equals(permissionCode));
            if (hasPermission) {
                return true;
            }
        }
        return false;
    }

    /** Day 15 §demo-data: plain-UUID unwrap of {@link #grantAccess}. */
    @Transactional
    public UUID grantAccessForSeed(UUID studentId, UUID guardianId, java.time.Instant expiresAt, java.util.Set<String> permissionCodes) {
        return switch (grantAccess(studentId, guardianId, expiresAt, permissionCodes)) {
            case GrantResult.Success s -> s.grantId();
            default -> throw new IllegalStateException("seed guardian access grant failed for student: " + studentId);
        };
    }

    public record MyGrant(UUID grantId, UUID studentId, String status, Instant expiresAt, Set<String> permissions) {
    }

    public record ManagedGrant(UUID grantId, UUID studentId, UUID guardianId, String status, Instant expiresAt, Set<String> permissions) {}

    @Transactional(readOnly = true)
    public List<ManagedGrant> listForStudent(UUID studentId) {
        UUID tenantId = studentProfileService.findTenantId(studentId).orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND));
        authorizationContext.assertCan(MANAGE_PERMISSION, ResourceRef.of("guardian_access_grant", UUID.randomUUID(), tenantId));
        Instant now = Instant.now();
        return grantRepository.findByStudentIdAndTenantId(studentId, tenantId).stream().map(g -> new ManagedGrant(
            g.id(), g.studentId(), g.guardianId(), g.revokedAt() != null ? "REVOKED" : g.isUsable(now) ? "ACTIVE" : "EXPIRED",
            g.expiresAt(), permissionRepository.findByGrantId(g.id()).stream().map(GuardianAccessPermission::permissionCode).collect(java.util.stream.Collectors.toSet())
        )).toList();
    }

    /**
     * Day 16 §guardian: self-service — before this, a guardian had no way to discover which students they can
     * see, or with which permissions, without an admin telling them out of band. {@code guardianId} is resolved
     * from the CURRENT principal only (never a caller-supplied id), so this can only ever return the caller's
     * own grants. Status is recomputed from {@link GuardianAccessGrant#isUsable} rather than trusted from the
     * raw {@code status} column, so a lapsed-but-not-yet-swept expiry still displays as EXPIRED, not ACTIVE.
     */
    @Transactional(readOnly = true)
    public List<MyGrant> listMyGrants(UUID tenantId) {
        UUID currentUserId = authorizationContext.currentUserId();
        UUID guardianId = guardianProfileService.findIdByTenantAndOwnerUserId(tenantId, currentUserId)
            .orElseThrow(() -> new org.springframework.security.access.AccessDeniedException(
                "authenticated principal is not a guardian in this tenant"));
        Instant now = Instant.now();
        return grantRepository.findByGuardianId(guardianId).stream()
            .map(g -> {
                String status = g.revokedAt() != null ? "REVOKED" : (g.isUsable(now) ? "ACTIVE" : "EXPIRED");
                Set<String> permissions = permissionRepository.findByGrantId(g.id()).stream()
                    .map(GuardianAccessPermission::permissionCode)
                    .collect(java.util.stream.Collectors.toSet());
                return new MyGrant(g.id(), g.studentId(), status, g.expiresAt(), permissions);
            })
            .toList();
    }
}
