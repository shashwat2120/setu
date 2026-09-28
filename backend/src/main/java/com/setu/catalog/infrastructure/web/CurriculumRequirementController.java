package com.setu.catalog.infrastructure.web;

import com.setu.catalog.application.CurriculumRequirementService;
import com.setu.catalog.domain.CurriculumRequirement;
import com.setu.catalog.domain.CurriculumRequirementCourse;
import com.setu.catalog.domain.RequirementRole;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/catalog/curriculum-requirements")
public class CurriculumRequirementController {

    private final CurriculumRequirementService requirementService;

    public CurriculumRequirementController(CurriculumRequirementService requirementService) {
        this.requirementService = requirementService;
    }

    public record CreateRequest(UUID curriculumId, String groupCode, BigDecimal minCredits, Integer minCourses, String ruleJson) {
    }

    public record RequirementResponse(UUID id, UUID curriculumId, String groupCode, BigDecimal minCredits,
                                       Integer minCourses, String ruleJson) {
    }

    private static RequirementResponse toResponse(CurriculumRequirement r) {
        return new RequirementResponse(r.id(), r.curriculumId(), r.groupCode(), r.minCredits(), r.minCourses(), r.ruleJson());
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody CreateRequest request) {
        var result = requirementService.create(request.curriculumId(), request.groupCode(), request.minCredits(),
            request.minCourses(), request.ruleJson());
        return switch (result) {
            case CurriculumRequirementService.CreateResult.Success success -> ResponseEntity.status(HttpStatus.CREATED)
                .body(requirementService.get(success.requirementId()).map(CurriculumRequirementController::toResponse).orElseThrow());
            case CurriculumRequirementService.CreateResult.CurriculumNotFound ignored ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("curriculum not found"));
            case CurriculumRequirementService.CreateResult.InvalidData invalid ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse(invalid.message()));
            case CurriculumRequirementService.CreateResult.InvalidRuleJson ignored ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("rule_json must be a valid JSON object"));
        };
    }

    @GetMapping("/{requirementId}")
    public ResponseEntity<?> get(@PathVariable UUID requirementId) {
        return requirementService.get(requirementId).map(r -> ResponseEntity.ok(toResponse(r)))
            .orElse(ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }

    @GetMapping
    public ResponseEntity<List<RequirementResponse>> listByCurriculum(@RequestParam UUID curriculumId) {
        return ResponseEntity.ok(requirementService.listByCurriculum(curriculumId).stream()
            .map(CurriculumRequirementController::toResponse).toList());
    }

    public record UpdateRequest(String groupCode, BigDecimal minCredits, Integer minCourses, String ruleJson) {
    }

    @PutMapping("/{requirementId}")
    public ResponseEntity<?> update(@PathVariable UUID requirementId, @RequestBody UpdateRequest request) {
        var result = requirementService.update(requirementId, request.groupCode(), request.minCredits(),
            request.minCourses(), request.ruleJson());
        return switch (result) {
            case CurriculumRequirementService.UpdateResult.Success ignored ->
                ResponseEntity.ok(requirementService.get(requirementId).map(CurriculumRequirementController::toResponse).orElseThrow());
            case CurriculumRequirementService.UpdateResult.NotFound ignored -> ResponseEntity.status(HttpStatus.NOT_FOUND).build();
            case CurriculumRequirementService.UpdateResult.InvalidData invalid ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse(invalid.message()));
            case CurriculumRequirementService.UpdateResult.InvalidRuleJson ignored ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("rule_json must be a valid JSON object"));
        };
    }

    public record AddCourseRequest(UUID courseId, RequirementRole requirementRole) {
    }

    public record RequirementCourseResponse(UUID id, UUID requirementId, UUID courseId, String requirementRole) {
    }

    @PostMapping("/{requirementId}/courses")
    public ResponseEntity<?> addCourse(@PathVariable UUID requirementId, @RequestBody AddCourseRequest request) {
        var result = requirementService.addCourse(requirementId, request.courseId(), request.requirementRole());
        return switch (result) {
            case CurriculumRequirementService.AddCourseResult.Success success ->
                ResponseEntity.status(HttpStatus.CREATED).body(new RequirementCourseResponse(success.linkId(),
                    requirementId, request.courseId(), request.requirementRole().name()));
            case CurriculumRequirementService.AddCourseResult.RequirementNotFound ignored ->
                ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse("requirement not found"));
            case CurriculumRequirementService.AddCourseResult.CourseNotFound ignored ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("course not found"));
            case CurriculumRequirementService.AddCourseResult.CourseTenantMismatch ignored ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("course belongs to a different tenant"));
            case CurriculumRequirementService.AddCourseResult.DuplicateMapping ignored ->
                ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse("course already mapped to this requirement"));
        };
    }

    @GetMapping("/{requirementId}/courses")
    public ResponseEntity<List<RequirementCourseResponse>> listCourses(@PathVariable UUID requirementId) {
        List<RequirementCourseResponse> courses = requirementService.listCourses(requirementId).stream()
            .map(c -> new RequirementCourseResponse(c.id(), c.requirementId(), c.courseId(), c.requirementRole().name()))
            .toList();
        return ResponseEntity.ok(courses);
    }

    @DeleteMapping("/{requirementId}/courses/{courseId}")
    public ResponseEntity<?> removeCourse(@PathVariable UUID requirementId, @PathVariable UUID courseId) {
        return switch (requirementService.removeCourse(requirementId, courseId)) {
            case SUCCESS -> ResponseEntity.noContent().build();
            case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        };
    }

    public record ErrorResponse(String message) {
    }
}
