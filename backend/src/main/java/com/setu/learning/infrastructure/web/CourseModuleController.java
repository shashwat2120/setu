package com.setu.learning.infrastructure.web;

import com.setu.learning.application.CourseModuleService;
import com.setu.learning.domain.CourseModule;
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

/** Day 10 §1: course_modules — see CourseModuleService's javadoc. */
@RestController
@RequestMapping("/learning/modules")
public class CourseModuleController {

    private final CourseModuleService courseModuleService;

    public CourseModuleController(CourseModuleService courseModuleService) {
        this.courseModuleService = courseModuleService;
    }

    public record CreateRequest(UUID sectionId, String title, int sequence) {
    }

    public record ModuleResponse(UUID id, UUID tenantId, UUID sectionId, String title, int sequence, String status) {
    }

    private static ModuleResponse toResponse(CourseModule m) {
        return new ModuleResponse(m.id(), m.tenantId(), m.sectionId(), m.title(), m.sequence(), m.status().name());
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody CreateRequest request) {
        var result = courseModuleService.create(request.sectionId(), request.title(), request.sequence());
        return switch (result) {
            case CourseModuleService.CreateResult.Success r -> ResponseEntity.status(HttpStatus.CREATED)
                .body(courseModuleService.get(r.moduleId()).map(CourseModuleController::toResponse).orElseThrow());
            case CourseModuleService.CreateResult.SectionNotFound ignored ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("section not found"));
            case CourseModuleService.CreateResult.InvalidModuleData invalid ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse(invalid.message()));
            case CourseModuleService.CreateResult.SequenceAlreadyInUse ignored ->
                ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse("a module already uses this sequence in this section"));
        };
    }

    @GetMapping("/{moduleId}")
    public ResponseEntity<?> get(@PathVariable UUID moduleId) {
        return courseModuleService.get(moduleId).map(m -> ResponseEntity.ok(toResponse(m)))
            .orElse(ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }

    @GetMapping
    public ResponseEntity<List<ModuleResponse>> listForSection(@RequestParam UUID sectionId, @RequestParam UUID tenantId) {
        return ResponseEntity.ok(courseModuleService.listForSection(sectionId, tenantId).stream()
            .map(CourseModuleController::toResponse).toList());
    }

    public record UpdateRequest(String title, int sequence) {
    }

    @PutMapping("/{moduleId}")
    public ResponseEntity<?> update(@PathVariable UUID moduleId, @RequestBody UpdateRequest request) {
        var result = courseModuleService.update(moduleId, request.title(), request.sequence());
        return switch (result) {
            case CourseModuleService.UpdateResult.Success ignored ->
                ResponseEntity.ok(courseModuleService.get(moduleId).map(CourseModuleController::toResponse).orElseThrow());
            case CourseModuleService.UpdateResult.NotFound ignored -> ResponseEntity.status(HttpStatus.NOT_FOUND).build();
            case CourseModuleService.UpdateResult.InvalidModuleData invalid ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse(invalid.message()));
            case CourseModuleService.UpdateResult.SequenceAlreadyInUse ignored ->
                ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse("a module already uses this sequence in this section"));
        };
    }

    @PostMapping("/{moduleId}/archive")
    public ResponseEntity<?> archive(@PathVariable UUID moduleId) {
        return setArchived(moduleId, true);
    }

    @PostMapping("/{moduleId}/reactivate")
    public ResponseEntity<?> reactivate(@PathVariable UUID moduleId) {
        return setArchived(moduleId, false);
    }

    private ResponseEntity<?> setArchived(UUID moduleId, boolean archived) {
        var result = courseModuleService.setArchived(moduleId, archived);
        return switch (result) {
            case SUCCESS -> ResponseEntity.ok(courseModuleService.get(moduleId).map(CourseModuleController::toResponse).orElseThrow());
            case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        };
    }

    public record ErrorResponse(String message) {
    }
}
