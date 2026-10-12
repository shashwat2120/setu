package com.setu.identity.infrastructure.web;

import com.setu.identity.application.UserAccessService;
import com.setu.identity.domain.UserStatus;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * HTTP surface for {@link UserAccessService} — the account-level access
 * lifecycle (ACTIVE/SUSPENDED/DEACTIVATED), separate from any module's
 * profile status. Every mutating operation requires a step-up-fresh bearer
 * token (the service asserts this itself); this controller adds no
 * authorization of its own, per this codebase's "never check a role in the
 * controller" rule (see RoleAssignmentController for the same pattern).
 */
@RestController
@RequestMapping("/identity/users")
public class UserAccessController {

    private final UserAccessService userAccessService;

    public UserAccessController(UserAccessService userAccessService) {
        this.userAccessService = userAccessService;
    }

    public record AccessActionRequest(UUID tenantId, String reason) {
    }

    public record StatusResponse(UserStatus status) {
    }

    @GetMapping("/{targetUserId}/status")
    public ResponseEntity<?> status(@PathVariable UUID targetUserId, @RequestParam UUID tenantId) {
        return userAccessService.status(targetUserId, tenantId)
            .map(status -> ResponseEntity.ok(new StatusResponse(status)))
            .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }

    @PostMapping("/{targetUserId}/suspend")
    public ResponseEntity<?> suspend(@PathVariable UUID targetUserId, @RequestBody AccessActionRequest request) {
        return switch (userAccessService.suspend(targetUserId, request.tenantId(), request.reason())) {
            case SUSPENDED -> ResponseEntity.noContent().build();
            case USER_NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).build();
            case ALREADY_SUSPENDED ->
                ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse("this account is already suspended"));
            case ALREADY_DEACTIVATED -> ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("this account is deactivated, not suspended — deactivation is terminal"));
        };
    }

    @PostMapping("/{targetUserId}/reactivate")
    public ResponseEntity<?> reactivate(@PathVariable UUID targetUserId, @RequestBody AccessActionRequest request) {
        return switch (userAccessService.reactivate(targetUserId, request.tenantId(), request.reason())) {
            case REACTIVATED -> ResponseEntity.noContent().build();
            case USER_NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).build();
            case NOT_SUSPENDED -> ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("this account is not currently suspended"));
        };
    }

    @PostMapping("/{targetUserId}/deactivate")
    public ResponseEntity<?> deactivate(@PathVariable UUID targetUserId, @RequestBody AccessActionRequest request) {
        return switch (userAccessService.deactivate(targetUserId, request.tenantId(), request.reason())) {
            case DEACTIVATED -> ResponseEntity.noContent().build();
            case USER_NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).build();
            case ALREADY_DEACTIVATED ->
                ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse("this account is already deactivated"));
        };
    }
}
