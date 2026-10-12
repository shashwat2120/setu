package com.setu.assessment.infrastructure.web;

import com.setu.assessment.application.AssessmentService;
import com.setu.assessment.domain.Assessment;
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

/** Day 11 §Assessment: assessments — see AssessmentService's javadoc. */
@RestController
@RequestMapping("/assessment/assessments")
public class AssessmentController {

    private final AssessmentService assessmentService;

    public AssessmentController(AssessmentService assessmentService) {
        this.assessmentService = assessmentService;
    }

    public record CreateRequest(UUID sectionId, String type, String title, BigDecimal totalMarks,
                                 Integer durationMinutes, Instant startsAt, Instant endsAt) {
    }

    public record AssessmentResponseBody(UUID id, UUID tenantId, UUID sectionId, String type, String title,
                                          BigDecimal totalMarks, Integer durationMinutes, Instant startsAt,
                                          Instant endsAt, String status) {
    }

    private static AssessmentResponseBody toResponse(Assessment a) {
        return new AssessmentResponseBody(a.id(), a.tenantId(), a.sectionId(), a.type(), a.title(), a.totalMarks(),
            a.durationMinutes(), a.startsAt(), a.endsAt(), a.status().name());
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody CreateRequest request) {
        var result = assessmentService.create(request.sectionId(), request.type(), request.title(), request.totalMarks(),
            request.durationMinutes(), request.startsAt(), request.endsAt());
        return switch (result) {
            case AssessmentService.CreateResult.Success r -> ResponseEntity.status(HttpStatus.CREATED)
                .body(assessmentService.get(r.assessmentId()).map(AssessmentController::toResponse).orElseThrow());
            case AssessmentService.CreateResult.SectionNotFound ignored ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("section not found"));
            case AssessmentService.CreateResult.InvalidAssessmentData invalid ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse(invalid.message()));
        };
    }

    @GetMapping("/{assessmentId}")
    public ResponseEntity<?> get(@PathVariable UUID assessmentId) {
        return assessmentService.get(assessmentId).map(a -> ResponseEntity.ok(toResponse(a)))
            .orElse(ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }

    @GetMapping
    public ResponseEntity<List<AssessmentResponseBody>> listForSection(@RequestParam UUID sectionId, @RequestParam UUID tenantId,
            @RequestParam(required = false) UUID guardianForStudentId) {
        return ResponseEntity.ok(assessmentService.listForSection(sectionId, tenantId, guardianForStudentId).stream()
            .map(AssessmentController::toResponse).toList());
    }

    public record UpdateRequest(String type, String title, BigDecimal totalMarks, Integer durationMinutes,
                                 Instant startsAt, Instant endsAt) {
    }

    @PutMapping("/{assessmentId}")
    public ResponseEntity<?> update(@PathVariable UUID assessmentId, @RequestBody UpdateRequest request) {
        var result = assessmentService.update(assessmentId, request.type(), request.title(), request.totalMarks(),
            request.durationMinutes(), request.startsAt(), request.endsAt());
        return switch (result) {
            case AssessmentService.UpdateResult.Success ignored ->
                ResponseEntity.ok(assessmentService.get(assessmentId).map(AssessmentController::toResponse).orElseThrow());
            case AssessmentService.UpdateResult.NotFound ignored -> ResponseEntity.status(HttpStatus.NOT_FOUND).build();
            case AssessmentService.UpdateResult.InvalidAssessmentData invalid ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse(invalid.message()));
        };
    }

    @PostMapping("/{assessmentId}/schedule")
    public ResponseEntity<?> schedule(@PathVariable UUID assessmentId) {
        return transitionResponse(assessmentId, assessmentService.schedule(assessmentId));
    }

    @PostMapping("/{assessmentId}/open")
    public ResponseEntity<?> open(@PathVariable UUID assessmentId) {
        return transitionResponse(assessmentId, assessmentService.open(assessmentId));
    }

    @PostMapping("/{assessmentId}/close")
    public ResponseEntity<?> close(@PathVariable UUID assessmentId) {
        return transitionResponse(assessmentId, assessmentService.close(assessmentId));
    }

    @PostMapping("/{assessmentId}/publish")
    public ResponseEntity<?> publish(@PathVariable UUID assessmentId) {
        return transitionResponse(assessmentId, assessmentService.publish(assessmentId));
    }

    @PostMapping("/{assessmentId}/void")
    public ResponseEntity<?> voidAssessment(@PathVariable UUID assessmentId) {
        return transitionResponse(assessmentId, assessmentService.voidAssessment(assessmentId));
    }

    private ResponseEntity<?> transitionResponse(UUID assessmentId, AssessmentService.TransitionResult result) {
        return switch (result) {
            case AssessmentService.TransitionResult.Success ignored ->
                ResponseEntity.ok(assessmentService.get(assessmentId).map(AssessmentController::toResponse).orElseThrow());
            case AssessmentService.TransitionResult.NotFound ignored -> ResponseEntity.status(HttpStatus.NOT_FOUND).build();
            case AssessmentService.TransitionResult.InvalidTransition ignored ->
                ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse("invalid status transition"));
            case AssessmentService.TransitionResult.ComponentWeightsInvalid invalid ->
                ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse(invalid.message()));
            case AssessmentService.TransitionResult.TooEarly ignored ->
                ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse("assessment cannot open before its start time"));
        };
    }

    public record ErrorResponse(String message) {
    }
}
