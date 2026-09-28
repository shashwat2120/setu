package com.setu.identity.infrastructure.web;

import com.setu.identity.application.RoleAssignmentService;
import com.setu.identity.domain.RoleCode;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Day 16 §super-admin: HTTP surface for {@link RoleAssignmentService} — the service itself already existed
 * (Day 4 §8) but had no controller. Both operations require a step-up-fresh bearer token (the service asserts
 * this itself via {@code AuthorizationContext#assertStepUp}); the frontend must complete {@code /auth/step-up}
 * and use the resulting freshly-issued token before calling either endpoint, exactly like any other step-up
 * gated action — this controller adds no authorization of its own, per this codebase's "never check a role in
 * the controller" rule.
 */
@RestController
@RequestMapping("/identity/roles")
public class RoleAssignmentController {

    private final RoleAssignmentService roleAssignmentService;

    public RoleAssignmentController(RoleAssignmentService roleAssignmentService) {
        this.roleAssignmentService = roleAssignmentService;
    }

    public record AssignRequest(UUID targetUserId, RoleCode roleCode, UUID tenantId) {
    }

    public record AssignResponse(UUID userRoleId) {
    }

    @PostMapping
    public ResponseEntity<AssignResponse> assign(@RequestBody AssignRequest request) {
        UUID assignmentId = roleAssignmentService.assignRole(request.targetUserId(), request.roleCode(), request.tenantId());
        return ResponseEntity.status(HttpStatus.CREATED).body(new AssignResponse(assignmentId));
    }

    @PostMapping("/{userRoleId}/revoke")
    public ResponseEntity<?> revoke(@PathVariable UUID userRoleId) {
        boolean revoked = roleAssignmentService.revokeRole(userRoleId);
        return revoked ? ResponseEntity.noContent().build() : ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }
}
