package com.setu.assessment.infrastructure.web;

import com.setu.assessment.application.AssessmentResponseService;
import com.setu.assessment.domain.AssessmentResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Day 11 §Responses: assessment_responses — see AssessmentResponseService's javadoc. */
@RestController
@RequestMapping("/assessment/responses")
public class AssessmentResponseController {

    private final AssessmentResponseService assessmentResponseService;

    public AssessmentResponseController(AssessmentResponseService assessmentResponseService) {
        this.assessmentResponseService = assessmentResponseService;
    }

    public record RecordRequest(UUID attemptId, UUID questionId, String answerJson) {
    }

    /** {@code isCorrect}/{@code pointsAwarded} are always included as-is (null until evaluated) — never a place a client can set them; see AssessmentResponseService's javadoc. */
    public record ResponseBody(UUID id, UUID attemptId, UUID questionId, String answerJson, Boolean isCorrect,
                                BigDecimal pointsAwarded) {
    }

    private static ResponseBody toResponse(AssessmentResponse r) {
        return new ResponseBody(r.id(), r.attemptId(), r.questionId(), r.answerJson(), r.isCorrect(), r.pointsAwarded());
    }

    @PostMapping
    public ResponseEntity<?> record(@RequestBody RecordRequest request) {
        var result = assessmentResponseService.recordResponse(request.attemptId(), request.questionId(), request.answerJson());
        return switch (result) {
            case AssessmentResponseService.RecordResult.Success r -> {
                var response = assessmentResponseService.listForAttempt(request.attemptId()).stream()
                    .filter(res -> res.id().equals(r.responseId())).findFirst().orElseThrow();
                yield ResponseEntity.ok(toResponse(response));
            }
            case AssessmentResponseService.RecordResult.AttemptNotFound ignored ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("attempt not found"));
            case AssessmentResponseService.RecordResult.NotOwner ignored ->
                ResponseEntity.status(HttpStatus.FORBIDDEN).body(new ErrorResponse("not your attempt"));
            case AssessmentResponseService.RecordResult.AttemptNotInProgress ignored ->
                ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse("attempt is no longer IN_PROGRESS"));
            case AssessmentResponseService.RecordResult.QuestionNotFound ignored ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("question not found"));
            case AssessmentResponseService.RecordResult.QuestionNotInAssessment ignored ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("question does not belong to this attempt's assessment"));
            case AssessmentResponseService.RecordResult.VersionConflict ignored ->
                ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse("response was updated concurrently; reload and retry"));
        };
    }

    @GetMapping
    public ResponseEntity<List<ResponseBody>> listForAttempt(@RequestParam UUID attemptId) {
        return ResponseEntity.ok(assessmentResponseService.listForAttempt(attemptId).stream()
            .map(AssessmentResponseController::toResponse).toList());
    }

    public record ErrorResponse(String message) {
    }
}
