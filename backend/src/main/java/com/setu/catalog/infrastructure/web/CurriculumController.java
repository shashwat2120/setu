package com.setu.catalog.infrastructure.web;

import com.setu.catalog.application.CurriculumService;
import com.setu.catalog.domain.Curriculum;
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

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Curriculum version CRUD (Day 6 §5 minimal reference, expanded Day 7 §4 to full versioning). */
@RestController
@RequestMapping("/catalog/curricula")
public class CurriculumController {

    private final CurriculumService curriculumService;

    public CurriculumController(CurriculumService curriculumService) {
        this.curriculumService = curriculumService;
    }

    public record CreateRequest(UUID programId, String name, String versionLabel, LocalDate effectiveFrom, LocalDate effectiveTo) {
    }

    public record CurriculumResponse(UUID id, UUID tenantId, UUID programId, String name, String versionLabel,
                                      LocalDate effectiveFrom, LocalDate effectiveTo) {
    }

    private static CurriculumResponse toResponse(Curriculum c) {
        return new CurriculumResponse(c.id(), c.tenantId(), c.programId(), c.name(), c.versionLabel(),
            c.effectiveFrom(), c.effectiveTo());
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody CreateRequest request) {
        var result = curriculumService.createReference(request.programId(), request.name(), request.versionLabel(),
            request.effectiveFrom(), request.effectiveTo());
        return switch (result) {
            case CurriculumService.CreateResult.Success success -> ResponseEntity.status(HttpStatus.CREATED)
                .body(curriculumService.get(success.curriculumId()).map(CurriculumController::toResponse).orElseThrow());
            case CurriculumService.CreateResult.ProgramNotFound ignored ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("program not found"));
            case CurriculumService.CreateResult.InvalidDateRange ignored ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("effective_to must be after effective_from"));
            case CurriculumService.CreateResult.OverlapsExistingVersion ignored ->
                ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse("curriculum version overlaps an existing version for this program"));
        };
    }

    @GetMapping("/{curriculumId}")
    public ResponseEntity<?> get(@PathVariable UUID curriculumId) {
        return curriculumService.get(curriculumId).map(c -> ResponseEntity.ok(toResponse(c)))
            .orElse(ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }

    @GetMapping
    public ResponseEntity<List<CurriculumResponse>> listByProgram(@RequestParam UUID programId, @RequestParam UUID tenantId) {
        return ResponseEntity.ok(curriculumService.listByProgram(programId, tenantId).stream()
            .map(CurriculumController::toResponse).toList());
    }

    public record UpdateRequest(String name, LocalDate effectiveTo) {
    }

    @PutMapping("/{curriculumId}")
    public ResponseEntity<?> update(@PathVariable UUID curriculumId, @RequestBody UpdateRequest request) {
        var result = curriculumService.update(curriculumId, request.name(), request.effectiveTo());
        return switch (result) {
            case CurriculumService.UpdateResult.Success ignored ->
                ResponseEntity.ok(curriculumService.get(curriculumId).map(CurriculumController::toResponse).orElseThrow());
            case CurriculumService.UpdateResult.NotFound ignored -> ResponseEntity.status(HttpStatus.NOT_FOUND).build();
            case CurriculumService.UpdateResult.InvalidDateRange ignored ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("invalid effective_to"));
            case CurriculumService.UpdateResult.OverlapsExistingVersion ignored ->
                ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse("curriculum version overlaps an existing version for this program"));
        };
    }

    public record ErrorResponse(String message) {
    }
}
