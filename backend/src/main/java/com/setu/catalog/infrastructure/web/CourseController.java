package com.setu.catalog.infrastructure.web;

import com.setu.catalog.application.CourseService;
import com.setu.catalog.domain.Course;
import com.setu.catalog.domain.CourseStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
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

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Course catalog CRUD/lifecycle (Day 7 §6). A course is a catalog DEFINITION — see CourseService's javadoc. */
@RestController
@RequestMapping("/catalog/courses")
public class CourseController {

    private final CourseService courseService;

    public CourseController(CourseService courseService) {
        this.courseService = courseService;
    }

    public record CreateRequest(UUID departmentId, String code, String title, BigDecimal credits, String courseType,
                                 String level, String description) {
    }

    public record CourseResponse(UUID id, UUID tenantId, UUID departmentId, String code, String title,
                                  BigDecimal credits, String courseType, String level, String description, String status) {
    }

    private static CourseResponse toResponse(Course c) {
        return new CourseResponse(c.id(), c.tenantId(), c.departmentId(), c.code(), c.title(), c.credits(),
            c.courseType(), c.level(), c.description(), c.status().name());
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody CreateRequest request) {
        var result = courseService.create(request.departmentId(), request.code(), request.title(), request.credits(),
            request.courseType(), request.level(), request.description());
        return switch (result) {
            case CourseService.CreateResult.Success success -> ResponseEntity.status(HttpStatus.CREATED)
                .body(courseService.get(success.courseId()).map(CourseController::toResponse).orElseThrow());
            case CourseService.CreateResult.DepartmentNotFound ignored ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("department not found"));
            case CourseService.CreateResult.CodeAlreadyInUse ignored ->
                ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse("course code already in use"));
            case CourseService.CreateResult.InvalidCourseData invalid ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse(invalid.message()));
        };
    }

    @GetMapping("/{courseId}")
    public ResponseEntity<?> get(@PathVariable UUID courseId) {
        return courseService.get(courseId).map(c -> ResponseEntity.ok(toResponse(c)))
            .orElse(ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }

    public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {
    }

    @GetMapping
    public ResponseEntity<PageResponse<CourseResponse>> listForTenant(@RequestParam UUID tenantId,
                                                                        @RequestParam(defaultValue = "0") int page,
                                                                        @RequestParam(defaultValue = "20") int size) {
        Page<Course> result = courseService.listForTenant(tenantId, PageRequest.of(page, size));
        return ResponseEntity.ok(new PageResponse<>(result.getContent().stream().map(CourseController::toResponse).toList(),
            result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages()));
    }

    public record UpdateRequest(String title, BigDecimal credits, String courseType, String level, String description) {
    }

    @PutMapping("/{courseId}")
    public ResponseEntity<?> update(@PathVariable UUID courseId, @RequestBody UpdateRequest request) {
        var result = courseService.update(courseId, request.title(), request.credits(), request.courseType(),
            request.level(), request.description());
        return switch (result) {
            case CourseService.UpdateResult.Success ignored ->
                ResponseEntity.ok(courseService.get(courseId).map(CourseController::toResponse).orElseThrow());
            case CourseService.UpdateResult.NotFound ignored -> ResponseEntity.status(HttpStatus.NOT_FOUND).build();
            case CourseService.UpdateResult.InvalidData invalid ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse(invalid.message()));
        };
    }

    public record StatusChangeRequest(CourseStatus status) {
    }

    @PostMapping("/{courseId}/status")
    public ResponseEntity<?> changeStatus(@PathVariable UUID courseId, @RequestBody StatusChangeRequest request) {
        return switch (courseService.changeStatus(courseId, request.status())) {
            case SUCCESS -> ResponseEntity.noContent().build();
            case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).build();
            case INVALID_TRANSITION -> ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("invalid status transition"));
        };
    }

    public record ErrorResponse(String message) {
    }
}
