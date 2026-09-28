package com.setu.assessment.infrastructure.web;

import com.setu.assessment.application.AssessmentAttemptService;
import com.setu.assessment.application.AssessmentEvaluationService;
import com.setu.assessment.domain.AssessmentAttempt;
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

/** Day 11 §Attempts: assessment_attempts — see AssessmentAttemptService's javadoc. */
@RestController
@RequestMapping("/assessment/attempts")
public class AssessmentAttemptController {

    private final AssessmentAttemptService assessmentAttemptService;
    private final AssessmentEvaluationService assessmentEvaluationService;

    public AssessmentAttemptController(AssessmentAttemptService assessmentAttemptService,
                                        AssessmentEvaluationService assessmentEvaluationService) {
        this.assessmentAttemptService = assessmentAttemptService;
        this.assessmentEvaluationService = assessmentEvaluationService;
    }

    public record StartRequest(UUID assessmentId) {
    }

    public record AttemptResponse(UUID id, UUID assessmentId, UUID studentId, int attemptNo, Instant startedAt,
                                   Instant submittedAt, String status, java.math.BigDecimal score) {
    }

    private static AttemptResponse toResponse(AssessmentAttempt a) {
        return new AttemptResponse(a.id(), a.assessmentId(), a.studentId(), a.attemptNo(), a.startedAt(),
            a.submittedAt(), a.status().name(), a.score());
    }

    @PostMapping
    public ResponseEntity<?> start(@RequestBody StartRequest request) {
        var result = assessmentAttemptService.start(request.assessmentId());
        return switch (result) {
            case AssessmentAttemptService.StartResult.Success r -> ResponseEntity.status(HttpStatus.CREATED)
                .body(assessmentAttemptService.get(r.attemptId()).map(AssessmentAttemptController::toResponse).orElseThrow());
            case AssessmentAttemptService.StartResult.AssessmentNotFound ignored ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("assessment not found"));
            case AssessmentAttemptService.StartResult.NotEnrolled ignored ->
                ResponseEntity.status(HttpStatus.FORBIDDEN).body(new ErrorResponse("not actively enrolled in this assessment's section"));
            case AssessmentAttemptService.StartResult.AssessmentNotOpen ignored ->
                ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse("assessment is not open"));
            case AssessmentAttemptService.StartResult.OutsideWindow ignored ->
                ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse("assessment window has ended"));
            case AssessmentAttemptService.StartResult.AlreadyAttempted ignored ->
                ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse("an attempt already exists for this assessment"));
        };
    }

    @GetMapping("/{attemptId}")
    public ResponseEntity<?> get(@PathVariable UUID attemptId) {
        return assessmentAttemptService.get(attemptId).map(a -> ResponseEntity.ok(toResponse(a)))
            .orElse(ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }

    @GetMapping
    public ResponseEntity<List<AttemptResponse>> listForAssessment(@RequestParam UUID assessmentId) {
        return ResponseEntity.ok(assessmentAttemptService.listForAssessment(assessmentId).stream()
            .map(AssessmentAttemptController::toResponse).toList());
    }

    @PostMapping("/{attemptId}/submit")
    public ResponseEntity<?> submit(@PathVariable UUID attemptId) {
        var result = assessmentAttemptService.submit(attemptId);
        return switch (result) {
            case AssessmentAttemptService.SubmitResult.Success ignored ->
                ResponseEntity.ok(assessmentAttemptService.get(attemptId).map(AssessmentAttemptController::toResponse).orElseThrow());
            case AssessmentAttemptService.SubmitResult.NotFound ignored -> ResponseEntity.status(HttpStatus.NOT_FOUND).build();
            case AssessmentAttemptService.SubmitResult.NotOwner ignored ->
                ResponseEntity.status(HttpStatus.FORBIDDEN).body(new ErrorResponse("not your attempt"));
            case AssessmentAttemptService.SubmitResult.InvalidTransition ignored ->
                ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse("invalid status transition"));
        };
    }

    @PostMapping("/{attemptId}/void")
    public ResponseEntity<?> voidAttempt(@PathVariable UUID attemptId) {
        var result = assessmentAttemptService.voidAttempt(attemptId);
        return switch (result) {
            case SUCCESS -> ResponseEntity.ok(assessmentAttemptService.get(attemptId).map(AssessmentAttemptController::toResponse).orElseThrow());
            case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).build();
            case INVALID_TRANSITION -> ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse("invalid status transition"));
        };
    }

    public record EvaluationResponse(java.math.BigDecimal totalScore, int autoGradedCount, int manualPendingCount) {
    }

    @PostMapping("/{attemptId}/evaluate")
    public ResponseEntity<?> evaluate(@PathVariable UUID attemptId) {
        var result = assessmentEvaluationService.evaluateAttempt(attemptId);
        return switch (result) {
            case AssessmentEvaluationService.EvaluateResult.Success r ->
                ResponseEntity.ok(new EvaluationResponse(r.totalScore(), r.autoGradedCount(), r.manualPendingCount()));
            case AssessmentEvaluationService.EvaluateResult.AttemptNotFound ignored -> ResponseEntity.status(HttpStatus.NOT_FOUND).build();
            case AssessmentEvaluationService.EvaluateResult.NotSubmitted ignored ->
                ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse("attempt is not SUBMITTED"));
        };
    }

    public record ErrorResponse(String message) {
    }
}
