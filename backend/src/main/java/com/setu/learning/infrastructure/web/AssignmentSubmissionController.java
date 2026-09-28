package com.setu.learning.infrastructure.web;

import com.setu.learning.application.AssignmentSubmissionService;
import com.setu.learning.domain.AssignmentSubmission;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Day 10 §6: assignment_submissions — see AssignmentSubmissionService's javadoc. */
@RestController
@RequestMapping("/learning/submissions")
public class AssignmentSubmissionController {

    private final AssignmentSubmissionService assignmentSubmissionService;

    public AssignmentSubmissionController(AssignmentSubmissionService assignmentSubmissionService) {
        this.assignmentSubmissionService = assignmentSubmissionService;
    }

    public record SubmitRequest(UUID assignmentId, String contentRef) {
    }

    public record SubmissionResponse(UUID id, UUID assignmentId, UUID studentId, int attemptNo, String submittedAt,
                                      String status, String contentRef) {
    }

    private static SubmissionResponse toResponse(AssignmentSubmission s) {
        return new SubmissionResponse(s.id(), s.assignmentId(), s.studentId(), s.attemptNo(),
            s.submittedAt() == null ? null : s.submittedAt().toString(), s.status().name(), s.contentRef());
    }

    @PostMapping
    public ResponseEntity<?> submit(@RequestBody SubmitRequest request) {
        var result = assignmentSubmissionService.submit(request.assignmentId(), request.contentRef());
        return switch (result) {
            case AssignmentSubmissionService.SubmitResult.Success r -> ResponseEntity.status(HttpStatus.CREATED)
                .body(new SubmitResponseBody(r.submissionId(), r.status().name(), r.attemptNo()));
            case AssignmentSubmissionService.SubmitResult.AssignmentNotFound ignored ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("assignment not found"));
            case AssignmentSubmissionService.SubmitResult.NotEnrolled ignored ->
                ResponseEntity.status(HttpStatus.FORBIDDEN).body(new ErrorResponse("not actively enrolled in this assignment's section"));
            case AssignmentSubmissionService.SubmitResult.AssignmentNotAvailable ignored ->
                ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse("assignment is not open for submission"));
            case AssignmentSubmissionService.SubmitResult.DeadlinePassed ignored ->
                ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse("submission deadline has passed and late submissions are not allowed"));
            case AssignmentSubmissionService.SubmitResult.DuplicateAttempt ignored ->
                ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse("this attempt was already submitted"));
        };
    }

    public record SubmitResponseBody(UUID submissionId, String status, int attemptNo) {
    }

    @GetMapping
    public ResponseEntity<List<SubmissionResponse>> listForAssignment(@RequestParam UUID assignmentId) {
        return ResponseEntity.ok(assignmentSubmissionService.listForAssignment(assignmentId).stream()
            .map(AssignmentSubmissionController::toResponse).toList());
    }

    public record ErrorResponse(String message) {
    }
}
