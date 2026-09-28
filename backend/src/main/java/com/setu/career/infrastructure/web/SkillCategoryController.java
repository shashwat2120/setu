package com.setu.career.infrastructure.web;

import com.setu.career.application.SkillCategoryService;
import com.setu.career.domain.SkillCategory;
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
@RequestMapping("/career/skill-categories")
public class SkillCategoryController {

    private final SkillCategoryService skillCategoryService;

    public SkillCategoryController(SkillCategoryService skillCategoryService) {
        this.skillCategoryService = skillCategoryService;
    }

    public record CreateRequest(UUID tenantId, String code, String name) {
    }

    public record UpdateRequest(String code, String name, int expectedVersion) {}

    public record CategoryResponse(UUID id, UUID tenantId, String code, String name, int version) {
    }

    private static CategoryResponse toResponse(SkillCategory c) {
        return new CategoryResponse(c.id(), c.tenantId(), c.code(), c.name(), c.version());
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody CreateRequest request) {
        var result = skillCategoryService.create(request.tenantId(), request.code(), request.name());
        return switch (result) {
            case SkillCategoryService.CreateResult.Success r -> ResponseEntity.status(HttpStatus.CREATED)
                .body(toResponse(skillCategoryService.listForTenant(request.tenantId()).stream()
                    .filter(c -> c.id().equals(r.categoryId())).findFirst().orElseThrow()));
            case SkillCategoryService.CreateResult.TenantNotFound ignored ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("tenant not found"));
            case SkillCategoryService.CreateResult.InvalidData invalid ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse(invalid.message()));
            case SkillCategoryService.CreateResult.CodeAlreadyInUse ignored ->
                ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse("category code already in use"));
        };
    }

    @GetMapping
    public ResponseEntity<List<CategoryResponse>> listForTenant(@RequestParam UUID tenantId) {
        return ResponseEntity.ok(skillCategoryService.listForTenant(tenantId).stream().map(SkillCategoryController::toResponse).toList());
    }

    @PutMapping("/{categoryId}")
    public ResponseEntity<?> update(@PathVariable UUID categoryId, @RequestBody UpdateRequest request) {
        return switch (skillCategoryService.update(categoryId, request.code(), request.name(), request.expectedVersion())) {
            case SkillCategoryService.UpdateResult.Success ignored -> ResponseEntity.ok(skillCategoryService.get(categoryId).map(SkillCategoryController::toResponse).orElseThrow());
            case SkillCategoryService.UpdateResult.NotFound ignored -> ResponseEntity.notFound().build();
            case SkillCategoryService.UpdateResult.InvalidData invalid -> ResponseEntity.badRequest().body(new ErrorResponse(invalid.message()));
            case SkillCategoryService.UpdateResult.CodeAlreadyInUse ignored -> ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse("category code already in use"));
            case SkillCategoryService.UpdateResult.VersionConflict ignored -> ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse("category changed; reload and retry"));
        };
    }

    public record ErrorResponse(String message) {
    }
}
