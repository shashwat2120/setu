package com.setu.guardianaccess.infrastructure.web;

import com.setu.guardianaccess.application.GuardianAccessService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Delegated guardian access grants/permissions (Day 6 §8) — security-sensitive; all rules enforced inside GuardianAccessService, never here. */
@RestController
@RequestMapping("/guardian-access/grants")
public class GuardianAccessController {

    private final GuardianAccessService guardianAccessService;

    public GuardianAccessController(GuardianAccessService guardianAccessService) {
        this.guardianAccessService = guardianAccessService;
    }

    public record GrantRequest(UUID studentId, UUID guardianId, Instant expiresAt, Set<String> permissions) {
    }

    public record GrantResponse(UUID grantId) {
    }

    @PostMapping
    public ResponseEntity<?> grant(@RequestBody GrantRequest request) {
        var result = guardianAccessService.grantAccess(request.studentId(), request.guardianId(), request.expiresAt(), request.permissions());
        return switch (result) {
            case GuardianAccessService.GrantResult.Success success ->
                ResponseEntity.status(HttpStatus.CREATED).body(new GrantResponse(success.grantId()));
            case GuardianAccessService.GrantResult.StudentNotFound ignored ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("student not found"));
            case GuardianAccessService.GrantResult.GuardianNotFound ignored ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("guardian not found"));
            case GuardianAccessService.GrantResult.TenantMismatch ignored ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("student and guardian belong to different colleges"));
            case GuardianAccessService.GrantResult.GuardianCannotGrantSelf ignored ->
                ResponseEntity.status(HttpStatus.FORBIDDEN).body(new ErrorResponse("a guardian cannot grant themselves access"));
            case GuardianAccessService.GrantResult.UnsupportedPermission unsupported -> ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("unsupported guardian permission: " + unsupported.code()));
        };
    }

    public record RevokeRequest(String reason) {
    }

    @DeleteMapping("/{grantId}")
    public ResponseEntity<?> revoke(@PathVariable UUID grantId, @RequestBody(required = false) RevokeRequest request) {
        String reason = request == null ? null : request.reason();
        return switch (guardianAccessService.revokeAccess(grantId, reason)) {
            case SUCCESS -> ResponseEntity.noContent().build();
            case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        };
    }

    public record MyGrantResponse(UUID grantId, UUID studentId, String status, Instant expiresAt, Set<String> permissions) {
    }

    @GetMapping
    public ResponseEntity<List<GuardianAccessService.ManagedGrant>> forStudent(@RequestParam UUID studentId) {
        return ResponseEntity.ok(guardianAccessService.listForStudent(studentId));
    }

    /** Day 16 §guardian: self-service — see {@code GuardianAccessService.listMyGrants}'s javadoc. tenantId is never inferred from the client beyond routing which tenant's grants to check; the guardianId lookup itself is always the CURRENT principal's own. */
    @GetMapping("/mine")
    public ResponseEntity<List<MyGrantResponse>> mine(@RequestParam UUID tenantId) {
        var grants = guardianAccessService.listMyGrants(tenantId).stream()
            .map(g -> new MyGrantResponse(g.grantId(), g.studentId(), g.status(), g.expiresAt(), g.permissions()))
            .toList();
        return ResponseEntity.ok(grants);
    }

    public record ErrorResponse(String message) {
    }
}
