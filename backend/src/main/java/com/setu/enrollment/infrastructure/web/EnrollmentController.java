package com.setu.enrollment.infrastructure.web;

import com.setu.enrollment.application.EnrollmentService;
import com.setu.enrollment.domain.Enrollment;
import com.setu.enrollment.domain.EnrollmentAction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Course-section enrollment request/drop (Day 8 §1-2) — see EnrollmentService's javadoc for the full invariant chain and locking strategy. */
@RestController
@RequestMapping("/enrollment/enrollments")
public class EnrollmentController {

    private final EnrollmentService enrollmentService;

    public EnrollmentController(EnrollmentService enrollmentService) {
        this.enrollmentService = enrollmentService;
    }

    public record RequestRequest(UUID studentId, UUID sectionId) {
    }

    public record EnrollmentResponse(UUID id, UUID tenantId, UUID sectionId, UUID studentId, String status,
                                      Instant requestedAt, Instant enrolledAt, Instant droppedAt, String finalStatus) {
    }

    private static EnrollmentResponse toResponse(Enrollment e) {
        return new EnrollmentResponse(e.id(), e.tenantId(), e.sectionId(), e.studentId(), e.status().name(),
            e.requestedAt(), e.enrolledAt(), e.droppedAt(), e.finalStatus());
    }

    @PostMapping
    public ResponseEntity<?> request(@RequestBody RequestRequest request) {
        var result = enrollmentService.request(request.studentId(), request.sectionId());
        return switch (result) {
            case EnrollmentService.RequestResult.Enrolled r -> ResponseEntity.status(HttpStatus.CREATED)
                .body(enrollmentService.get(r.enrollmentId()).map(EnrollmentController::toResponse).orElseThrow());
            case EnrollmentService.RequestResult.Waitlisted r -> ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(new WaitlistedResponse(r.enrollmentId(), r.position()));
            case EnrollmentService.RequestResult.Rejected r ->
                ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse(r.reason().name()));
            case EnrollmentService.RequestResult.StudentNotFound ignored ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("student not found"));
            case EnrollmentService.RequestResult.SectionNotFound ignored ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("section not found"));
            case EnrollmentService.RequestResult.TenantMismatch ignored ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("section belongs to a different tenant"));
            case EnrollmentService.RequestResult.DuplicateActiveEnrollment ignored ->
                ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse("student already has an active enrollment for this section"));
            case EnrollmentService.RequestResult.AlreadyCompleted ignored ->
                ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse("this section was already completed by this student"));
        };
    }

    public record WaitlistedResponse(UUID enrollmentId, int position) {
    }

    @GetMapping("/{enrollmentId}")
    public ResponseEntity<?> get(@PathVariable UUID enrollmentId) {
        return enrollmentService.get(enrollmentId).map(e -> ResponseEntity.ok(toResponse(e)))
            .orElse(ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }

    public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {
    }

    @GetMapping
    public ResponseEntity<PageResponse<EnrollmentResponse>> listForStudent(@RequestParam UUID studentId,
                                                                            @RequestParam(defaultValue = "0") int page,
                                                                            @RequestParam(defaultValue = "20") int size) {
        Page<Enrollment> result = enrollmentService.listForStudent(studentId, PageRequest.of(page, size));
        return ResponseEntity.ok(new PageResponse<>(result.getContent().stream().map(EnrollmentController::toResponse).toList(),
            result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages()));
    }

    @GetMapping("/section/{sectionId}")
    public ResponseEntity<PageResponse<EnrollmentResponse>> listForSection(@PathVariable UUID sectionId, @RequestParam UUID tenantId,
                                                                            @RequestParam(defaultValue = "0") int page,
                                                                            @RequestParam(defaultValue = "20") int size) {
        Page<Enrollment> result = enrollmentService.listForSection(sectionId, tenantId, PageRequest.of(page, size));
        return ResponseEntity.ok(new PageResponse<>(result.getContent().stream().map(EnrollmentController::toResponse).toList(),
            result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages()));
    }

    public record DropRequest(String reason) {
    }

    @PostMapping("/{enrollmentId}/drop")
    public ResponseEntity<?> drop(@PathVariable UUID enrollmentId, @RequestBody DropRequest request) {
        return switch (enrollmentService.drop(enrollmentId, request.reason())) {
            case EnrollmentService.DropResult.Success r -> ResponseEntity.ok(new DropResponse(r.promotedWaitlist()));
            case EnrollmentService.DropResult.NotFound ignored -> ResponseEntity.status(HttpStatus.NOT_FOUND).build();
            case EnrollmentService.DropResult.InvalidTransition ignored ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("enrollment is not in a droppable state"));
            case EnrollmentService.DropResult.StudentNotFound ignored ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("student not found"));
        };
    }

    public record DropResponse(boolean promotedWaitlist) {
    }

    public record ActionResponse(UUID id, String action, UUID actorUserId, String reason, Instant createdAt) {
    }

    @GetMapping("/{enrollmentId}/actions")
    public ResponseEntity<List<ActionResponse>> history(@PathVariable UUID enrollmentId) {
        List<ActionResponse> actions = enrollmentService.history(enrollmentId).stream()
            .map(a -> new ActionResponse(a.id(), a.action(), a.actorUserId(), a.reason(), a.createdAt()))
            .toList();
        return ResponseEntity.ok(actions);
    }

    public record ErrorResponse(String message) {
    }
}
