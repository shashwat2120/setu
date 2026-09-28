package com.setu.career.infrastructure.web;

import com.setu.career.application.JobSkillService;
import com.setu.career.domain.JobSkill;
import com.setu.career.domain.ProficiencyLevel;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/career/job-skills")
public class JobSkillController {

    private final JobSkillService jobSkillService;

    public JobSkillController(JobSkillService jobSkillService) {
        this.jobSkillService = jobSkillService;
    }

    public record AddRequest(UUID jobId, UUID skillId, ProficiencyLevel requiredLevel) {
    }

    public record JobSkillResponse(UUID id, UUID tenantId, UUID jobId, UUID skillId, ProficiencyLevel requiredLevel) {
    }

    private static JobSkillResponse toResponse(JobSkill s) {
        return new JobSkillResponse(s.id(), s.tenantId(), s.jobId(), s.skillId(), s.requiredLevel());
    }

    @PostMapping
    public ResponseEntity<?> add(@RequestBody AddRequest request) {
        var result = jobSkillService.add(request.jobId(), request.skillId(), request.requiredLevel());
        return switch (result) {
            case JobSkillService.AddResult.Success r -> ResponseEntity.status(HttpStatus.CREATED)
                .body(jobSkillService.listForJob(request.jobId()).stream()
                    .filter(s -> s.id().equals(r.jobSkillId())).findFirst().map(JobSkillController::toResponse).orElseThrow());
            case JobSkillService.AddResult.JobNotFound ignored ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("job not found"));
            case JobSkillService.AddResult.SkillNotFound ignored ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("skill not found"));
            case JobSkillService.AddResult.AlreadyRequired ignored ->
                ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse("this skill is already required for this job"));
        };
    }

    @GetMapping
    public ResponseEntity<List<JobSkillResponse>> listForJob(@RequestParam UUID jobId) {
        return ResponseEntity.ok(jobSkillService.listForJob(jobId).stream().map(JobSkillController::toResponse).toList());
    }

    @DeleteMapping("/{jobSkillId}")
    public ResponseEntity<?> remove(@PathVariable UUID jobSkillId) {
        return switch (jobSkillService.remove(jobSkillId)) {
            case JobSkillService.RemoveResult.Success ignored -> ResponseEntity.noContent().build();
            case JobSkillService.RemoveResult.NotFound ignored -> ResponseEntity.notFound().build();
        };
    }

    public record ErrorResponse(String message) {
    }
}
