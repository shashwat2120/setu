package com.setu.directory.infrastructure.web;

import com.setu.directory.application.StudentProfileService;
import com.setu.directory.domain.StudentProfile;
import com.setu.directory.domain.StudentStatus;
import com.setu.shared.identity.PersonDirectory;
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

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Student admission/lifecycle API (Day 6 §3/§12). All authorization happens inside StudentProfileService via the Day 4 engine. */
@RestController
@RequestMapping("/directory/students")
public class StudentController {

    private final StudentProfileService studentProfileService;
    private final PersonDirectory personDirectory;

    public StudentController(StudentProfileService studentProfileService, PersonDirectory personDirectory) {
        this.studentProfileService = studentProfileService;
        this.personDirectory = personDirectory;
    }

    public record AdmitRequest(UUID tenantId, UUID personId, String admissionNo, String rollNo, LocalDate admissionDate) {
    }

    public record StudentResponse(UUID id, UUID tenantId, UUID personId, String admissionNo, String status,
                                   String fullName, String email) {
    }

    private StudentResponse toResponse(StudentProfile p) {
        var person = personDirectory.findById(p.personId());
        String fullName = person.map(PersonDirectory.PersonSummary::legalName).orElse(null);
        String email = person.map(PersonDirectory.PersonSummary::email).orElse(null);
        return new StudentResponse(p.id(), p.tenantId(), p.personId(), p.admissionNo(), p.status().name(), fullName, email);
    }

    public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {
    }

    @GetMapping
    public ResponseEntity<PageResponse<StudentResponse>> list(@RequestParam UUID tenantId,
                                                                @RequestParam(defaultValue = "0") int page,
                                                                @RequestParam(defaultValue = "20") int size) {
        Page<StudentProfile> result = studentProfileService.listForTenant(tenantId, PageRequest.of(page, size));
        return ResponseEntity.ok(new PageResponse<>(result.getContent().stream().map(this::toResponse).toList(),
            result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages()));
    }

    @PostMapping
    public ResponseEntity<?> admit(@RequestBody AdmitRequest request) {
        var result = studentProfileService.admitStudent(request.tenantId(), request.personId(), request.admissionNo(),
            request.rollNo(), request.admissionDate());
        return switch (result) {
            case StudentProfileService.AdmitResult.Success success -> ResponseEntity.status(HttpStatus.CREATED)
                .body(studentProfileService.get(success.studentId()).map(this::toResponse).orElseThrow());
            case StudentProfileService.AdmitResult.PersonNotFound ignored ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("person not found"));
            case StudentProfileService.AdmitResult.PersonAlreadyHasProfileInTenant ignored ->
                ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse("person already has a student profile in this college"));
            case StudentProfileService.AdmitResult.DuplicateAdmissionNo ignored ->
                ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse("admission number already in use"));
        };
    }

    @GetMapping("/{studentId}")
    public ResponseEntity<?> get(@PathVariable UUID studentId) {
        return studentProfileService.get(studentId)
            .map(p -> ResponseEntity.ok(toResponse(p)))
            .orElse(ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }

    public record StatusChangeRequest(StudentStatus status, String reason) {
    }

    @PostMapping("/{studentId}/status")
    public ResponseEntity<?> changeStatus(@PathVariable UUID studentId, @RequestBody StatusChangeRequest request) {
        var result = studentProfileService.changeStatus(studentId, request.status(), request.reason());
        return switch (result) {
            case SUCCESS -> ResponseEntity.noContent().build();
            case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).build();
            case INVALID_TRANSITION -> ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("invalid status transition"));
        };
    }

    public record ErrorResponse(String message) {
    }
}
