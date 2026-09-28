package com.setu.career.infrastructure.web;

import com.setu.career.application.JobService;
import com.setu.career.domain.Job;
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

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/career/jobs")
public class JobController {

    private final JobService jobService;

    public JobController(JobService jobService) {
        this.jobService = jobService;
    }

    public record CreateRequest(UUID tenantId, String title, String employer, String description) {
    }

    public record UpdateRequest(String title, String employer, String description, int expectedVersion) {}

    public record JobResponse(UUID id, UUID tenantId, UUID postedBy, String title, String employer, String description, String status, int version) {
    }

    private static JobResponse toResponse(Job j) {
        return new JobResponse(j.id(), j.tenantId(), j.postedBy(), j.title(), j.employer(), j.description(), j.status().name(), j.version());
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody CreateRequest request) {
        var result = jobService.create(request.tenantId(), request.title(), request.employer(), request.description());
        return switch (result) {
            case JobService.CreateResult.Success r -> ResponseEntity.status(HttpStatus.CREATED)
                .body(jobService.get(r.jobId()).map(JobController::toResponse).orElseThrow());
            case JobService.CreateResult.TenantNotFound ignored ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("tenant not found"));
            case JobService.CreateResult.InvalidData invalid ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse(invalid.message()));
        };
    }

    @GetMapping("/{jobId}")
    public ResponseEntity<?> get(@PathVariable UUID jobId) {
        return jobService.get(jobId).map(j -> ResponseEntity.ok(toResponse(j)))
            .orElse(ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }

    @GetMapping
    public ResponseEntity<List<JobResponse>> listForTenant(@RequestParam UUID tenantId) {
        return ResponseEntity.ok(jobService.listForTenant(tenantId).stream().map(JobController::toResponse).toList());
    }

    @PostMapping("/{jobId}/close")
    public ResponseEntity<?> close(@PathVariable UUID jobId) {
        return switch (jobService.close(jobId)) {
            case JobService.CloseResult.Success ignored -> ResponseEntity.noContent().build();
            case JobService.CloseResult.NotFound ignored -> ResponseEntity.status(HttpStatus.NOT_FOUND).build();
            case JobService.CloseResult.InvalidTransition ignored ->
                ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse("invalid status transition"));
        };
    }

    @PutMapping("/{jobId}")
    public ResponseEntity<?> update(@PathVariable UUID jobId, @RequestBody UpdateRequest request) {
        return switch (jobService.update(jobId, request.title(), request.employer(), request.description(), request.expectedVersion())) {
            case JobService.UpdateResult.Success ignored -> jobService.get(jobId).map(j -> ResponseEntity.ok(toResponse(j))).orElseGet(() -> ResponseEntity.notFound().build());
            case JobService.UpdateResult.NotFound ignored -> ResponseEntity.notFound().build();
            case JobService.UpdateResult.InvalidData invalid -> ResponseEntity.badRequest().body(new ErrorResponse(invalid.message()));
            case JobService.UpdateResult.InvalidTransition ignored -> ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse("only open jobs can be edited"));
            case JobService.UpdateResult.VersionConflict ignored -> ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse("job changed; reload and retry"));
        };
    }

    public record ErrorResponse(String message) {
    }
}
