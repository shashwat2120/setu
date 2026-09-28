package com.setu.enrollment.infrastructure.web;

import com.setu.enrollment.application.FacultyAssignmentService;
import com.setu.enrollment.domain.FacultyAssignment;
import com.setu.enrollment.domain.FacultyAssignmentRole;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Faculty-to-section assignments (Day 7 §9) — see FacultyAssignmentService's javadoc. */
@RestController
@RequestMapping("/enrollment/faculty-assignments")
public class FacultyAssignmentController {

    private final FacultyAssignmentService facultyAssignmentService;

    public FacultyAssignmentController(FacultyAssignmentService facultyAssignmentService) {
        this.facultyAssignmentService = facultyAssignmentService;
    }

    public record AssignRequest(UUID sectionId, UUID facultyId, FacultyAssignmentRole role, BigDecimal allocationPct,
                                 LocalDate startsAt, LocalDate endsAt) {
    }

    public record AssignmentResponse(UUID id, UUID tenantId, UUID sectionId, UUID facultyId, String role,
                                      BigDecimal allocationPct, LocalDate startsAt, LocalDate endsAt, boolean active) {
    }

    private static AssignmentResponse toResponse(FacultyAssignment a) {
        return new AssignmentResponse(a.id(), a.tenantId(), a.sectionId(), a.facultyId(), a.role().name(),
            a.allocationPct(), a.startsAt(), a.endsAt(), a.isActive());
    }

    @PostMapping
    public ResponseEntity<?> assign(@RequestBody AssignRequest request) {
        var result = facultyAssignmentService.assign(request.sectionId(), request.facultyId(), request.role(),
            request.allocationPct(), request.startsAt(), request.endsAt());
        return switch (result) {
            case FacultyAssignmentService.AssignResult.Success success -> ResponseEntity.status(HttpStatus.CREATED)
                .body(facultyAssignmentService.get(success.assignmentId()).map(FacultyAssignmentController::toResponse).orElseThrow());
            case FacultyAssignmentService.AssignResult.SectionNotFound ignored ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("section not found"));
            case FacultyAssignmentService.AssignResult.FacultyNotFound ignored ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("faculty not found"));
            case FacultyAssignmentService.AssignResult.TenantMismatch ignored ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("faculty belongs to a different tenant"));
            case FacultyAssignmentService.AssignResult.InvalidAssignmentData invalid ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse(invalid.message()));
            case FacultyAssignmentService.AssignResult.PrimaryAlreadyAssigned ignored ->
                ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse("this section already has an active PRIMARY assignment"));
        };
    }

    @GetMapping("/{assignmentId}")
    public ResponseEntity<?> get(@PathVariable UUID assignmentId) {
        return facultyAssignmentService.get(assignmentId).map(a -> ResponseEntity.ok(toResponse(a)))
            .orElse(ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }

    @GetMapping
    public ResponseEntity<List<AssignmentResponse>> listForSection(@RequestParam(required = false) UUID sectionId,
                                                                     @RequestParam(required = false) UUID facultyId,
                                                                     @RequestParam UUID tenantId) {
        List<FacultyAssignment> result = sectionId != null
            ? facultyAssignmentService.listForSection(sectionId, tenantId)
            : facultyAssignmentService.listForFaculty(facultyId, tenantId);
        return ResponseEntity.ok(result.stream().map(FacultyAssignmentController::toResponse).toList());
    }

    public record UpdateRequest(BigDecimal allocationPct, LocalDate startsAt) {
    }

    @PutMapping("/{assignmentId}")
    public ResponseEntity<?> update(@PathVariable UUID assignmentId, @RequestBody UpdateRequest request) {
        var result = facultyAssignmentService.update(assignmentId, request.allocationPct(), request.startsAt());
        return switch (result) {
            case FacultyAssignmentService.UpdateResult.Success ignored ->
                ResponseEntity.ok(facultyAssignmentService.get(assignmentId).map(FacultyAssignmentController::toResponse).orElseThrow());
            case FacultyAssignmentService.UpdateResult.NotFound ignored -> ResponseEntity.status(HttpStatus.NOT_FOUND).build();
            case FacultyAssignmentService.UpdateResult.InvalidAssignmentData invalid ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse(invalid.message()));
        };
    }

    public record EndRequest(LocalDate endsAt) {
    }

    @PostMapping("/{assignmentId}/end")
    public ResponseEntity<?> end(@PathVariable UUID assignmentId, @RequestBody EndRequest request) {
        var result = facultyAssignmentService.end(assignmentId, request.endsAt());
        return switch (result) {
            case FacultyAssignmentService.EndResult.Success ignored -> ResponseEntity.noContent().build();
            case FacultyAssignmentService.EndResult.NotFound ignored -> ResponseEntity.status(HttpStatus.NOT_FOUND).build();
            case FacultyAssignmentService.EndResult.InvalidEndDate invalid ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse(invalid.message()));
        };
    }

    public record ErrorResponse(String message) {
    }
}
