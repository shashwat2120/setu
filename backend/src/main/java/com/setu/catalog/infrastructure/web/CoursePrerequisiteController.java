package com.setu.catalog.infrastructure.web;

import com.setu.catalog.application.CoursePrerequisiteService;
import com.setu.catalog.domain.PrerequisiteRuleType;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/catalog/courses/{courseId}/prerequisites")
public class CoursePrerequisiteController {

    private final CoursePrerequisiteService prerequisiteService;

    public CoursePrerequisiteController(CoursePrerequisiteService prerequisiteService) {
        this.prerequisiteService = prerequisiteService;
    }

    public record AddRequest(UUID prerequisiteCourseId, PrerequisiteRuleType ruleType, String minimumGrade) {
    }

    public record PrerequisiteResponse(UUID id, UUID courseId, UUID prerequisiteCourseId, String ruleType, String minimumGrade) {
    }

    @PostMapping
    public ResponseEntity<?> add(@PathVariable UUID courseId, @RequestBody AddRequest request) {
        var result = prerequisiteService.addPrerequisite(courseId, request.prerequisiteCourseId(), request.ruleType(),
            request.minimumGrade());
        return switch (result) {
            case CoursePrerequisiteService.AddResult.Success success -> ResponseEntity.status(HttpStatus.CREATED)
                .body(new PrerequisiteResponse(success.edgeId(), courseId, request.prerequisiteCourseId(),
                    request.ruleType().name(), request.minimumGrade()));
            case CoursePrerequisiteService.AddResult.CourseNotFound ignored ->
                ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse("course not found"));
            case CoursePrerequisiteService.AddResult.PrerequisiteCourseNotFound ignored ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("prerequisite course not found"));
            case CoursePrerequisiteService.AddResult.TenantMismatch ignored ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("prerequisite course belongs to a different tenant"));
            case CoursePrerequisiteService.AddResult.SelfReference ignored ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("a course cannot be its own prerequisite"));
            case CoursePrerequisiteService.AddResult.InvalidMinimumGrade ignored ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("unsupported minimum_grade"));
            case CoursePrerequisiteService.AddResult.DuplicateEdge ignored ->
                ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse("this prerequisite edge already exists"));
            case CoursePrerequisiteService.AddResult.WouldCreateCycle ignored ->
                ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse("this edge would create a prerequisite cycle"));
        };
    }

    @GetMapping
    public ResponseEntity<List<PrerequisiteResponse>> list(@PathVariable UUID courseId) {
        List<PrerequisiteResponse> prerequisites = prerequisiteService.listForCourse(courseId).stream()
            .map(p -> new PrerequisiteResponse(p.id(), p.courseId(), p.prerequisiteCourseId(), p.ruleType().name(), p.minimumGrade()))
            .toList();
        return ResponseEntity.ok(prerequisites);
    }

    @DeleteMapping("/{prerequisiteCourseId}")
    public ResponseEntity<?> remove(@PathVariable UUID courseId, @PathVariable UUID prerequisiteCourseId) {
        return switch (prerequisiteService.removePrerequisite(courseId, prerequisiteCourseId)) {
            case SUCCESS -> ResponseEntity.noContent().build();
            case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        };
    }

    public record ErrorResponse(String message) {
    }
}
