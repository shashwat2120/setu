package com.setu.enrollment.infrastructure.web;

import com.setu.enrollment.application.EnrollmentOverrideService;
import com.setu.enrollment.domain.EnrollmentOverride;
import com.setu.enrollment.domain.OverrideType;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/enrollment/overrides")
public class EnrollmentOverrideController {

    private final EnrollmentOverrideService overrideService;

    public EnrollmentOverrideController(EnrollmentOverrideService overrideService) {
        this.overrideService = overrideService;
    }

    public record CreateRequest(UUID studentId, UUID sectionId, OverrideType type, String reason, Instant expiresAt) {
    }

    public record OverrideResponse(UUID id, UUID tenantId, UUID studentId, UUID sectionId, String type, String status,
                                    String reason, UUID approvedBy, Instant expiresAt) {
    }

    private static OverrideResponse toResponse(EnrollmentOverride o) {
        return new OverrideResponse(o.id(), o.tenantId(), o.studentId(), o.sectionId(), o.type().name(),
            o.status().name(), o.reason(), o.approvedBy(), o.expiresAt());
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody CreateRequest request) {
        var result = overrideService.create(request.studentId(), request.sectionId(), request.type(), request.reason(), request.expiresAt());
        return switch (result) {
            case EnrollmentOverrideService.CreateResult.Success success -> ResponseEntity.status(HttpStatus.CREATED)
                .body(overrideService.listForStudent(request.studentId()).stream()
                    .filter(o -> o.id().equals(success.overrideId())).findFirst().map(EnrollmentOverrideController::toResponse).orElseThrow());
            case EnrollmentOverrideService.CreateResult.StudentNotFound ignored ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("student not found"));
            case EnrollmentOverrideService.CreateResult.SectionNotFound ignored ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("section not found"));
            case EnrollmentOverrideService.CreateResult.TenantMismatch ignored ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("section belongs to a different tenant"));
            case EnrollmentOverrideService.CreateResult.SelfApprovalRejected ignored ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("approver cannot be the beneficiary student"));
            case EnrollmentOverrideService.CreateResult.InvalidData invalid ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse(invalid.message()));
        };
    }

    @GetMapping
    public ResponseEntity<List<OverrideResponse>> listForStudent(@RequestParam UUID studentId) {
        return ResponseEntity.ok(overrideService.listForStudent(studentId).stream().map(EnrollmentOverrideController::toResponse).toList());
    }

    @PostMapping("/{overrideId}/revoke")
    public ResponseEntity<?> revoke(@PathVariable UUID overrideId) {
        return switch (overrideService.revoke(overrideId)) {
            case SUCCESS -> ResponseEntity.noContent().build();
            case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).build();
            case INVALID_TRANSITION -> ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("override is not revocable"));
        };
    }

    public record ErrorResponse(String message) {
    }
}
