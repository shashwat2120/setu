package com.setu.identity.application;

import com.setu.identity.domain.RoleCode;
import com.setu.identity.domain.UserRole;
import com.setu.identity.domain.UserRoleAssignedEvent;
import com.setu.identity.domain.UserRoleRevokedEvent;
import com.setu.identity.infrastructure.RoleRepository;
import com.setu.identity.infrastructure.UserRoleRepository;
import com.setu.shared.authorization.AuthorizationContext;
import com.setu.shared.authorization.ResourceRef;
import com.setu.shared.governance.AuditLogWriter;
import com.setu.shared.governance.OutboxEventWriter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Role/permission changes — a sensitive operation per Day 4 instruction §8: requires the actor to hold
 * MANAGE_TENANT_ROLES (tenant-scoped) or MANAGE_ROLES_PLATFORM (platform-scoped grants), and every change is
 * audited. Authorization goes through {@link AuthorizationContext#assertCan}, never a bare role check — defense
 * in depth means this would still reject an unauthorized caller even if a controller forgot to check first.
 * <p>
 * Step-up (fresh passkey reauthentication) is required only for {@link #ELEVATED_ROLES} — a genuine privilege
 * escalation onto staff/administrative authority. It is deliberately NOT required for {@code STUDENT} or
 * {@code GUARDIAN}: granting the very first, expected role to someone a college admin is routinely admitting or
 * registering is not a privilege escalation the way handing out {@code FACULTY}/{@code COLLEGE_ADMIN}/
 * {@code PLACEMENT_OFFICER}/{@code SUPER_ADMIN} is — it's normal onboarding traffic, and gating it behind a
 * hardware-passkey ceremony would make routine student/guardian admission impossible for any account without
 * one registered. Explicit product decision, not a workaround: {@link #assignRole}/{@link #revokeRole} still go
 * through the exact same {@code MANAGE_TENANT_ROLES}/{@code MANAGE_ROLES_PLATFORM} permission check and audit
 * write for every role, elevated or not — only the step-up freshness proof is scoped.
 * <p>
 * {@code @NamedInterface}: the student-admission orchestration in {@code shared.admin} calls
 * {@link #assignRole} directly to grant a freshly-admitted student their STUDENT role, the same cross-module
 * pattern {@code shared.seed.DemoDataSeeder} already uses against this module's other {@code @NamedInterface}
 * services.
 */
@org.springframework.modulith.NamedInterface
@Service
public class RoleAssignmentService {

    private static final Set<RoleCode> ELEVATED_ROLES = EnumSet.of(
        RoleCode.SUPER_ADMIN, RoleCode.COLLEGE_ADMIN, RoleCode.FACULTY, RoleCode.PLACEMENT_OFFICER);

    private final RoleRepository roleRepository;
    private final UserRoleRepository userRoleRepository;
    private final AuthorizationContext authorizationContext;
    private final AuditLogWriter auditLogWriter;
    private final OutboxEventWriter outboxEventWriter;

    public RoleAssignmentService(RoleRepository roleRepository, UserRoleRepository userRoleRepository,
                                  AuthorizationContext authorizationContext, AuditLogWriter auditLogWriter,
                                  OutboxEventWriter outboxEventWriter) {
        this.roleRepository = roleRepository;
        this.userRoleRepository = userRoleRepository;
        this.authorizationContext = authorizationContext;
        this.auditLogWriter = auditLogWriter;
        this.outboxEventWriter = outboxEventWriter;
    }

    @Transactional
    public UUID assignRole(UUID targetUserId, RoleCode roleCode, UUID tenantId) {
        if (ELEVATED_ROLES.contains(roleCode)) {
            authorizationContext.assertStepUp();
        }
        String requiredPermission = tenantId == null ? "MANAGE_ROLES_PLATFORM" : "MANAGE_TENANT_ROLES";
        authorizationContext.assertCan(requiredPermission, ResourceRef.of("user_role", targetUserId, tenantId));

        UUID actorUserId = authorizationContext.currentUserId();
        var role = roleRepository.findByCode(roleCode)
            .orElseThrow(() -> new IllegalArgumentException("Unknown role code: " + roleCode));
        Instant now = Instant.now();
        UserRole assignment = new UserRole(UUID.randomUUID(), targetUserId, role.id(), tenantId, now, null);
        userRoleRepository.save(assignment);

        auditLogWriter.write(tenantId, actorUserId, "ROLE_ASSIGNED", "identity.user_roles", assignment.id(),
            Map.of("targetUserId", targetUserId, "roleCode", roleCode.name()));
        var event = new UserRoleAssignedEvent(UUID.randomUUID(), now, assignment.id(), UUID.randomUUID(), null, tenantId,
            assignment.id(), targetUserId, roleCode.name(), actorUserId);
        outboxEventWriter.write(event.aggregateType(), event.aggregateId(), event.eventType(), event.eventVersion(), event,
            event.tenantId(), event.correlationId(), event.causationId());
        return assignment.id();
    }

    @Transactional
    public boolean revokeRole(UUID userRoleId) {
        var maybeAssignment = userRoleRepository.findById(userRoleId);
        if (maybeAssignment.isEmpty()) {
            return false;
        }
        UserRole assignment = maybeAssignment.get();
        // Same ELEVATED_ROLES scoping as assignRole — revoking a routine STUDENT/GUARDIAN grant is exactly as
        // low-risk as granting one, so it gets the same treatment for the same reason.
        roleRepository.findById(assignment.roleId())
            .filter(role -> ELEVATED_ROLES.contains(role.code()))
            .ifPresent(role -> authorizationContext.assertStepUp());
        String requiredPermission = assignment.tenantId() == null ? "MANAGE_ROLES_PLATFORM" : "MANAGE_TENANT_ROLES";
        authorizationContext.assertCan(requiredPermission,
            ResourceRef.of("user_role", assignment.id(), assignment.tenantId()));

        UUID actorUserId = authorizationContext.currentUserId();
        Instant now = Instant.now();
        assignment.endNow(now);
        userRoleRepository.save(assignment);

        auditLogWriter.write(assignment.tenantId(), actorUserId, "ROLE_REVOKED", "identity.user_roles",
            assignment.id(), Map.of("targetUserId", assignment.userId()));
        String roleCode = roleRepository.findById(assignment.roleId()).map(r -> r.code().name()).orElse("UNKNOWN");
        var event = new UserRoleRevokedEvent(UUID.randomUUID(), now, assignment.id(), UUID.randomUUID(), null, assignment.tenantId(),
            assignment.id(), assignment.userId(), roleCode, actorUserId);
        outboxEventWriter.write(event.aggregateType(), event.aggregateId(), event.eventType(), event.eventVersion(), event,
            event.tenantId(), event.correlationId(), event.causationId());
        return true;
    }
}
