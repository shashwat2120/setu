package com.setu.learning.infrastructure.web;

import com.setu.learning.application.AssignmentService;
import com.setu.learning.domain.Assignment;
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
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Day 10 §5: assignments — see AssignmentService's javadoc. */
@RestController
@RequestMapping("/learning/assignments")
public class AssignmentController {

    private final AssignmentService assignmentService;

    public AssignmentController(AssignmentService assignmentService) {
        this.assignmentService = assignmentService;
    }

    public record CreateRequest(UUID sectionId, String title, BigDecimal maxMarks, Instant dueAt, String latePolicy) {
    }

    public record AssignmentResponse(UUID id, UUID tenantId, UUID sectionId, String title, BigDecimal maxMarks,
                                      Instant dueAt, String latePolicy, String status) {
    }

    private static AssignmentResponse toResponse(Assignment a) {
        return new AssignmentResponse(a.id(), a.tenantId(), a.sectionId(), a.title(), a.maxMarks(), a.dueAt(),
            a.latePolicy(), a.status().name());
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody CreateRequest request) {
        var result = assignmentService.create(request.sectionId(), request.title(), request.maxMarks(), request.dueAt(), request.latePolicy());
        return switch (result) {
            case AssignmentService.CreateResult.Success r -> ResponseEntity.status(HttpStatus.CREATED)
                .body(assignmentService.get(r.assignmentId()).map(AssignmentController::toResponse).orElseThrow());
            case AssignmentService.CreateResult.SectionNotFound ignored ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("section not found"));
            case AssignmentService.CreateResult.InvalidAssignmentData invalid ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse(invalid.message()));
        };
    }

    @GetMapping("/{assignmentId}")
    public ResponseEntity<?> get(@PathVariable UUID assignmentId) {
        return assignmentService.get(assignmentId).map(a -> ResponseEntity.ok(toResponse(a)))
            .orElse(ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }

    @GetMapping
    public ResponseEntity<List<AssignmentResponse>> listForSection(@RequestParam UUID sectionId, @RequestParam UUID tenantId) {
        return ResponseEntity.ok(assignmentService.listForSection(sectionId, tenantId).stream()
            .map(AssignmentController::toResponse).toList());
    }

    public record UpdateRequest(String title, BigDecimal maxMarks, Instant dueAt, String latePolicy) {
    }

    @PutMapping("/{assignmentId}")
    public ResponseEntity<?> update(@PathVariable UUID assignmentId, @RequestBody UpdateRequest request) {
        var result = assignmentService.update(assignmentId, request.title(), request.maxMarks(), request.dueAt(), request.latePolicy());
        return switch (result) {
            case AssignmentService.UpdateResult.Success ignored ->
                ResponseEntity.ok(assignmentService.get(assignmentId).map(AssignmentController::toResponse).orElseThrow());
            case AssignmentService.UpdateResult.NotFound ignored -> ResponseEntity.status(HttpStatus.NOT_FOUND).build();
            case AssignmentService.UpdateResult.InvalidAssignmentData invalid ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse(invalid.message()));
        };
    }

    @PostMapping("/{assignmentId}/publish")
    public ResponseEntity<?> publish(@PathVariable UUID assignmentId) {
        return transitionResponse(assignmentId, assignmentService.publish(assignmentId));
    }

    @PostMapping("/{assignmentId}/close")
    public ResponseEntity<?> close(@PathVariable UUID assignmentId) {
        return transitionResponse(assignmentId, assignmentService.close(assignmentId));
    }

    private ResponseEntity<?> transitionResponse(UUID assignmentId, AssignmentService.TransitionResult result) {
        return switch (result) {
            case SUCCESS -> ResponseEntity.ok(assignmentService.get(assignmentId).map(AssignmentController::toResponse).orElseThrow());
            case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).build();
            case INVALID_TRANSITION -> ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse("invalid status transition"));
        };
    }

    public record ErrorResponse(String message) {
    }
}
