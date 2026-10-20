package com.setu.career.infrastructure.web;

import com.setu.career.application.ApplicationService;
import com.setu.career.domain.Application;
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
@RequestMapping("/career/applications")
public class ApplicationController {

    private final ApplicationService applicationService;

    public ApplicationController(ApplicationService applicationService) {
        this.applicationService = applicationService;
    }

    public record ApplyRequest(UUID jobId, UUID studentId) {
    }

    public record ApplicationResponse(UUID id, UUID tenantId, UUID jobId, UUID studentId, String status, Instant appliedAt, String notes, String decisionReason,
                                      Instant decidedAt) {
    }

    private static ApplicationResponse toResponse(Application a) {
        return new ApplicationResponse(a.id(), a.tenantId(), a.jobId(), a.studentId(), a.status().name(), a.appliedAt(), a.notes(), a.decisionReason(), a.decidedAt());
    }

    /** The reason is shown to the student; required to reject, optional otherwise. */
    public record DecisionRequest(String reason, java.math.BigDecimal packageLpa, java.time.LocalDate joiningDate) {
    }

    @PostMapping
    public ResponseEntity<?> apply(@RequestBody ApplyRequest request) {
        var result = applicationService.apply(request.jobId(), request.studentId());
        return switch (result) {
            case ApplicationService.ApplyResult.Success r -> ResponseEntity.status(HttpStatus.CREATED)
                .body(applicationService.listForStudent(request.studentId()).stream()
                    .filter(a -> a.id().equals(r.applicationId())).findFirst().map(ApplicationController::toResponse).orElseThrow());
            case ApplicationService.ApplyResult.JobNotFound ignored ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("job not found"));
            case ApplicationService.ApplyResult.JobNotOpen ignored ->
                ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse("this job is no longer open"));
            case ApplicationService.ApplyResult.JobFull ignored ->
                ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse("all the openings for this job have been filled"));
            case ApplicationService.ApplyResult.StudentNotFound ignored ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("student not found"));
            case ApplicationService.ApplyResult.NotEligible r ->
                ResponseEntity.status(HttpStatus.CONFLICT).body(new NotEligibleResponse(r.missingOrInsufficientSkillIds(), r.unmetRules()));
            case ApplicationService.ApplyResult.AlreadyApplied ignored ->
                ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse("already applied to this job"));
        };
    }

    /** Why the application was refused: the required skills the student lacks, and the deadline or academic rules they do not meet (each with a sentence for the student). */
    public record NotEligibleResponse(List<UUID> missingOrInsufficientSkillIds, List<ApplicationService.UnmetRule> unmetRules) {
    }

    @GetMapping("/eligibility")
    public ResponseEntity<?> previewEligibility(@RequestParam UUID jobId, @RequestParam UUID studentId) {
        return applicationService.previewEligibility(jobId, studentId)
            .map(r -> ResponseEntity.ok(new EligibilityResponse(r.eligible(), r.missingOrInsufficientSkillIds(), r.unmetRules())))
            .orElse(ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }

    public record EligibilityResponse(boolean eligible, List<UUID> missingOrInsufficientSkillIds, List<ApplicationService.UnmetRule> unmetRules) {
    }

    @GetMapping
    public ResponseEntity<List<ApplicationResponse>> listForStudent(@RequestParam UUID studentId) {
        return ResponseEntity.ok(applicationService.listForStudent(studentId).stream().map(ApplicationController::toResponse).toList());
    }

    @GetMapping("/by-job")
    public ResponseEntity<List<ApplicationResponse>> listForJob(@RequestParam UUID jobId) {
        return ResponseEntity.ok(applicationService.listForJob(jobId).stream().map(ApplicationController::toResponse).toList());
    }

    private ResponseEntity<?> handleTransition(ApplicationService.TransitionResult result) {
        return switch (result) {
            case ApplicationService.TransitionResult.Success ignored -> ResponseEntity.noContent().build();
            case ApplicationService.TransitionResult.NotFound ignored -> ResponseEntity.status(HttpStatus.NOT_FOUND).build();
            case ApplicationService.TransitionResult.InvalidTransition ignored ->
                ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse("invalid status transition"));
            case ApplicationService.TransitionResult.ReasonRequired ignored ->
                ResponseEntity.badRequest().body(new ErrorResponse("a rejection needs a reason, which the student is shown"));
            case ApplicationService.TransitionResult.InvalidOffer r -> ResponseEntity.badRequest().body(new ErrorResponse(r.message()));
            case ApplicationService.TransitionResult.OpeningsFilled r ->
                ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse("all " + r.openings() + " opening" + (r.openings() == 1 ? " is" : "s are") + " already filled"));
            case ApplicationService.TransitionResult.ReasonTooLong ignored ->
                ResponseEntity.badRequest().body(new ErrorResponse("the reason must be at most " + ApplicationService.MAX_REASON_LENGTH + " characters"));
        };
    }

    @PostMapping("/{applicationId}/shortlist")
    public ResponseEntity<?> shortlist(@PathVariable UUID applicationId, @RequestBody(required = false) DecisionRequest body) {
        return handleTransition(applicationService.shortlist(applicationId, body == null ? null : body.reason()));
    }

    @PostMapping("/{applicationId}/reject")
    public ResponseEntity<?> reject(@PathVariable UUID applicationId, @RequestBody(required = false) DecisionRequest body) {
        return handleTransition(applicationService.reject(applicationId, body == null ? null : body.reason()));
    }

    @PostMapping("/{applicationId}/select")
    public ResponseEntity<?> select(@PathVariable UUID applicationId, @RequestBody(required = false) DecisionRequest body) {
        return handleTransition(applicationService.select(applicationId, body == null ? null : body.reason(),
            body == null ? null : new ApplicationService.Offer(body.packageLpa(), body.joiningDate())));
    }

    @PostMapping("/{applicationId}/withdraw")
    public ResponseEntity<?> withdraw(@PathVariable UUID applicationId) {
        return handleTransition(applicationService.withdraw(applicationId));
    }

    public record ErrorResponse(String message) {
    }
}
