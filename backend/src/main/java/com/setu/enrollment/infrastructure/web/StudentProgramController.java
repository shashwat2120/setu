package com.setu.enrollment.infrastructure.web;

import com.setu.enrollment.application.StudentProgramService;
import com.setu.enrollment.domain.StudentProgram;
import com.setu.enrollment.domain.StudentProgramStatus;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Student-program history API (Day 6 §6) — deliberately NOT course enrollment/timetable. */
@RestController
@RequestMapping("/enrollment/student-programs")
public class StudentProgramController {

    private final StudentProgramService studentProgramService;

    public StudentProgramController(StudentProgramService studentProgramService) {
        this.studentProgramService = studentProgramService;
    }

    public record AssignRequest(UUID studentId, UUID programId, UUID curriculumId, int admissionYear,
                                 boolean primary, LocalDate startsAt) {
    }

    public record StudentProgramResponse(UUID id, UUID tenantId, UUID studentId, UUID programId, String status, boolean primary) {
    }

    private static StudentProgramResponse toResponse(StudentProgram sp) {
        return new StudentProgramResponse(sp.id(), sp.tenantId(), sp.studentId(), sp.programId(), sp.status().name(), sp.isPrimary());
    }

    @PostMapping
    public ResponseEntity<?> assign(@RequestBody AssignRequest request) {
        var result = studentProgramService.assign(request.studentId(), request.programId(), request.curriculumId(),
            request.admissionYear(), request.primary(), request.startsAt());
        return switch (result) {
            case StudentProgramService.AssignResult.Success success -> {
                var history = studentProgramService.history(request.studentId());
                yield ResponseEntity.status(HttpStatus.CREATED).body(history.stream()
                    .filter(sp -> sp.id().equals(success.studentProgramId())).findFirst()
                    .map(StudentProgramController::toResponse).orElseThrow());
            }
            case StudentProgramService.AssignResult.StudentNotFound ignored ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("student not found"));
            case StudentProgramService.AssignResult.ProgramNotFound ignored ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("program not found"));
            case StudentProgramService.AssignResult.TenantMismatch ignored ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("student and program belong to different colleges"));
            case StudentProgramService.AssignResult.CurriculumProgramMismatch ignored ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("curriculum does not belong to this program"));
            case StudentProgramService.AssignResult.InvalidDates invalid ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse(invalid.message()));
            case StudentProgramService.AssignResult.DuplicateActivePrimaryProgram ignored ->
                ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse("student already has an active primary program"));
            case StudentProgramService.AssignResult.SecondaryProgramNotAllowedByPolicy ignored -> ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("this college does not allow a second active program (allow_dual_program is off)"));
        };
    }

    @GetMapping
    public ResponseEntity<List<StudentProgramResponse>> history(@org.springframework.web.bind.annotation.RequestParam UUID studentId) {
        return ResponseEntity.ok(studentProgramService.history(studentId).stream().map(StudentProgramController::toResponse).toList());
    }

    public record StatusChangeRequest(StudentProgramStatus status) {
    }

    @PostMapping("/{studentProgramId}/status")
    public ResponseEntity<?> changeStatus(@PathVariable UUID studentProgramId, @RequestBody StatusChangeRequest request) {
        return switch (studentProgramService.changeStatus(studentProgramId, request.status())) {
            case SUCCESS -> ResponseEntity.noContent().build();
            case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).build();
            case INVALID_TRANSITION -> ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("invalid status transition"));
        };
    }

    public record ErrorResponse(String message) {
    }
}
