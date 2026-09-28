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

    public record ApplicationResponse(UUID id, UUID tenantId, UUID jobId, UUID studentId, String status, Instant appliedAt, String notes) {
    }

    private static ApplicationResponse toResponse(Application a) {
        return new ApplicationResponse(a.id(), a.tenantId(), a.jobId(), a.studentId(), a.status().name(), a.appliedAt(), a.notes());
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
            case ApplicationService.ApplyResult.StudentNotFound ignored ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("student not found"));
            case ApplicationService.ApplyResult.NotEligible r ->
                ResponseEntity.status(HttpStatus.CONFLICT).body(new NotEligibleResponse(r.missingOrInsufficientSkillIds()));
            case ApplicationService.ApplyResult.AlreadyApplied ignored ->
                ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse("already applied to this job"));
        };
    }

    public record NotEligibleResponse(List<UUID> missingOrInsufficientSkillIds) {
    }

    @GetMapping("/eligibility")
    public ResponseEntity<?> previewEligibility(@RequestParam UUID jobId, @RequestParam UUID studentId) {
        return applicationService.previewEligibility(jobId, studentId)
            .map(r -> ResponseEntity.ok(new EligibilityResponse(r.eligible(), r.missingOrInsufficientSkillIds())))
            .orElse(ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }

    public record EligibilityResponse(boolean eligible, List<UUID> missingOrInsufficientSkillIds) {
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
        };
    }

    @PostMapping("/{applicationId}/shortlist")
    public ResponseEntity<?> shortlist(@PathVariable UUID applicationId) {
        return handleTransition(applicationService.shortlist(applicationId));
    }

    @PostMapping("/{applicationId}/reject")
    public ResponseEntity<?> reject(@PathVariable UUID applicationId) {
        return handleTransition(applicationService.reject(applicationId));
    }

    @PostMapping("/{applicationId}/select")
    public ResponseEntity<?> select(@PathVariable UUID applicationId) {
        return handleTransition(applicationService.select(applicationId));
    }

    @PostMapping("/{applicationId}/withdraw")
    public ResponseEntity<?> withdraw(@PathVariable UUID applicationId) {
        return handleTransition(applicationService.withdraw(applicationId));
    }

    public record ErrorResponse(String message) {
    }
}
