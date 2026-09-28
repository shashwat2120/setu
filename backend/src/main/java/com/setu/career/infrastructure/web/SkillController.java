package com.setu.career.infrastructure.web;

import com.setu.career.application.SkillService;
import com.setu.career.domain.Skill;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/career/skills")
public class SkillController {

    private final SkillService skillService;

    public SkillController(SkillService skillService) {
        this.skillService = skillService;
    }

    public record CreateRequest(UUID tenantId, String code, String name, UUID categoryId) {
    }

    public record UpdateRequest(String code, String name, UUID categoryId, int expectedVersion) {}

    public record SkillResponse(UUID id, UUID tenantId, String code, String name, UUID categoryId, int version) {
    }

    private static SkillResponse toResponse(Skill s) {
        return new SkillResponse(s.id(), s.tenantId(), s.code(), s.name(), s.categoryId(), s.version());
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody CreateRequest request) {
        var result = skillService.create(request.tenantId(), request.code(), request.name(), request.categoryId());
        return switch (result) {
            case SkillService.CreateResult.Success r -> ResponseEntity.status(HttpStatus.CREATED)
                .body(toResponse(skillService.listForTenant(request.tenantId()).stream()
                    .filter(s -> s.id().equals(r.skillId())).findFirst().orElseThrow()));
            case SkillService.CreateResult.TenantNotFound ignored ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("tenant not found"));
            case SkillService.CreateResult.CategoryNotFound ignored ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("category not found"));
            case SkillService.CreateResult.InvalidData invalid ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse(invalid.message()));
            case SkillService.CreateResult.CodeAlreadyInUse ignored ->
                ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse("skill code already in use"));
        };
    }

    @GetMapping
    public ResponseEntity<List<SkillResponse>> listForTenant(@RequestParam UUID tenantId) {
        return ResponseEntity.ok(skillService.listForTenant(tenantId).stream().map(SkillController::toResponse).toList());
    }

    @PutMapping("/{skillId}")
    public ResponseEntity<?> update(@PathVariable UUID skillId, @RequestBody UpdateRequest request) {
        var result = skillService.update(skillId, request.code(), request.name(), request.categoryId(), request.expectedVersion());
        return switch (result) {
            case SkillService.UpdateResult.Success ignored -> skillService.get(skillId).map(s -> ResponseEntity.ok(toResponse(s))).orElseGet(() -> ResponseEntity.notFound().build());
            case SkillService.UpdateResult.NotFound ignored -> ResponseEntity.notFound().build();
            case SkillService.UpdateResult.CategoryNotFound ignored -> ResponseEntity.badRequest().body(new ErrorResponse("category not found in this tenant"));
            case SkillService.UpdateResult.InvalidData invalid -> ResponseEntity.badRequest().body(new ErrorResponse(invalid.message()));
            case SkillService.UpdateResult.CodeAlreadyInUse ignored -> ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse("skill code already in use"));
            case SkillService.UpdateResult.VersionConflict ignored -> ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse("skill changed; reload and retry"));
        };
    }

    public record ErrorResponse(String message) {
    }
}
