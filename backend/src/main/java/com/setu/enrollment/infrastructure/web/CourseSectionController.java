package com.setu.enrollment.infrastructure.web;

import com.setu.enrollment.application.CourseSectionService;
import com.setu.enrollment.domain.CourseSection;
import com.setu.enrollment.domain.CourseSectionStatus;
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

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Concrete operational course-section offerings (Day 7 §8) — see CourseSectionService's javadoc. */
@RestController
@RequestMapping("/enrollment/course-sections")
public class CourseSectionController {

    private final CourseSectionService courseSectionService;

    public CourseSectionController(CourseSectionService courseSectionService) {
        this.courseSectionService = courseSectionService;
    }

    public record CreateRequest(UUID courseId, UUID periodId, UUID classId, String sectionCode, int capacity,
                                 UUID roomId, Instant registrationOpenAt, Instant registrationCloseAt) {
    }

    public record SectionResponse(UUID id, UUID tenantId, UUID courseId, UUID periodId, UUID classId,
                                   String sectionCode, int capacity, UUID roomId, String status,
                                   Instant registrationOpenAt, Instant registrationCloseAt) {
    }

    private static SectionResponse toResponse(CourseSection s) {
        return new SectionResponse(s.id(), s.tenantId(), s.courseId(), s.periodId(), s.classId(), s.sectionCode(),
            s.capacity(), s.roomId(), s.status().name(), s.registrationOpenAt(), s.registrationCloseAt());
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody CreateRequest request) {
        var result = courseSectionService.create(request.courseId(), request.periodId(), request.classId(),
            request.sectionCode(), request.capacity(), request.roomId(), request.registrationOpenAt(),
            request.registrationCloseAt());
        return switch (result) {
            case CourseSectionService.CreateResult.Success success -> ResponseEntity.status(HttpStatus.CREATED)
                .body(courseSectionService.get(success.sectionId()).map(CourseSectionController::toResponse).orElseThrow());
            case CourseSectionService.CreateResult.CourseNotFound ignored ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("course not found"));
            case CourseSectionService.CreateResult.PeriodNotFound ignored ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("academic period not found"));
            case CourseSectionService.CreateResult.ClassNotFound ignored ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("class not found"));
            case CourseSectionService.CreateResult.RoomNotFound ignored ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("room not found"));
            case CourseSectionService.CreateResult.TenantMismatch mismatch ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse(mismatch.resource() + " belongs to a different tenant"));
            case CourseSectionService.CreateResult.RoomCapacityInsufficient ignored ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("room capacity is insufficient for this section's capacity"));
            case CourseSectionService.CreateResult.InvalidSectionData invalid ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse(invalid.message()));
            case CourseSectionService.CreateResult.SectionCodeAlreadyInUse ignored ->
                ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse("section_code already in use for this course/period"));
        };
    }

    @GetMapping("/{sectionId}")
    public ResponseEntity<?> get(@PathVariable UUID sectionId) {
        return courseSectionService.get(sectionId).map(s -> ResponseEntity.ok(toResponse(s)))
            .orElse(ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }

    public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {
    }

    @GetMapping
    public ResponseEntity<PageResponse<SectionResponse>> listForTenant(@RequestParam UUID tenantId,
                                                                         @RequestParam(defaultValue = "0") int page,
                                                                         @RequestParam(defaultValue = "20") int size) {
        Page<CourseSection> result = courseSectionService.listForTenant(tenantId, PageRequest.of(page, size));
        return ResponseEntity.ok(new PageResponse<>(result.getContent().stream().map(CourseSectionController::toResponse).toList(),
            result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages()));
    }

    @GetMapping("/open-for-registration")
    public ResponseEntity<PageResponse<SectionResponse>> openForRegistration(@RequestParam UUID tenantId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "100") int size) {
        Page<CourseSection> result = courseSectionService.listOpenForRegistration(tenantId, PageRequest.of(page, size));
        return ResponseEntity.ok(new PageResponse<>(result.getContent().stream().map(CourseSectionController::toResponse).toList(),
            result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages()));
    }

    public record UpdateRequest(String sectionCode, int capacity, Instant registrationOpenAt, Instant registrationCloseAt) {
    }

    @PutMapping("/{sectionId}")
    public ResponseEntity<?> update(@PathVariable UUID sectionId, @RequestBody UpdateRequest request) {
        var result = courseSectionService.update(sectionId, request.sectionCode(), request.capacity(),
            request.registrationOpenAt(), request.registrationCloseAt());
        return switch (result) {
            case CourseSectionService.UpdateResult.Success ignored ->
                ResponseEntity.ok(courseSectionService.get(sectionId).map(CourseSectionController::toResponse).orElseThrow());
            case CourseSectionService.UpdateResult.NotFound ignored -> ResponseEntity.status(HttpStatus.NOT_FOUND).build();
            case CourseSectionService.UpdateResult.InvalidSectionData invalid ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse(invalid.message()));
        };
    }

    public record ChangeRoomRequest(UUID roomId) {
    }

    @PostMapping("/{sectionId}/room")
    public ResponseEntity<?> changeRoom(@PathVariable UUID sectionId, @RequestBody ChangeRoomRequest request) {
        return switch (courseSectionService.changeRoom(sectionId, request.roomId())) {
            case CourseSectionService.ChangeRoomResult.Success ignored -> ResponseEntity.noContent().build();
            case CourseSectionService.ChangeRoomResult.NotFound ignored -> ResponseEntity.status(HttpStatus.NOT_FOUND).build();
            case CourseSectionService.ChangeRoomResult.RoomNotFound ignored ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("room not found"));
            case CourseSectionService.ChangeRoomResult.TenantMismatch ignored ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("room belongs to a different tenant"));
            case CourseSectionService.ChangeRoomResult.RoomCapacityInsufficient ignored ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("room capacity is insufficient for this section's capacity"));
        };
    }

    public record StatusChangeRequest(CourseSectionStatus status) {
    }

    @PostMapping("/{sectionId}/status")
    public ResponseEntity<?> changeStatus(@PathVariable UUID sectionId, @RequestBody StatusChangeRequest request) {
        return switch (courseSectionService.changeStatus(sectionId, request.status())) {
            case SUCCESS -> ResponseEntity.noContent().build();
            case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).build();
            case INVALID_TRANSITION -> ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("invalid status transition"));
        };
    }

    public record ErrorResponse(String message) {
    }
}
