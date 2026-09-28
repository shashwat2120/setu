package com.setu.shared.admin.web;

import com.setu.shared.admin.StudentAdmissionService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

/** HTTP surface for {@link StudentAdmissionService} — see that class's own javadoc for the full authorization/design rationale. */
@RestController
@RequestMapping("/admin/students")
public class StudentAdmissionController {

    private final StudentAdmissionService studentAdmissionService;

    public StudentAdmissionController(StudentAdmissionService studentAdmissionService) {
        this.studentAdmissionService = studentAdmissionService;
    }

    public record AdmitNewStudentRequest(UUID tenantId, String fullName, String preferredName, LocalDate dob,
                                          String gender, String email, String phone, String admissionNo,
                                          String rollNo, LocalDate admissionDate) {
    }

    public record AdmitNewStudentResponse(UUID userId, UUID personId, UUID studentId) {
    }

    public record ErrorResponse(String message) {
    }

    @PostMapping
    public ResponseEntity<?> admitNewStudent(@RequestBody AdmitNewStudentRequest request) {
        var result = studentAdmissionService.admitNewStudent(request.tenantId(), request.fullName(),
            request.preferredName(), request.dob(), request.gender(), request.email(), request.phone(),
            request.admissionNo(), request.rollNo(), request.admissionDate());
        return switch (result) {
            case StudentAdmissionService.AdmitNewStudentResult.Success success -> ResponseEntity.status(HttpStatus.CREATED)
                .body(new AdmitNewStudentResponse(success.userId(), success.personId(), success.studentId()));
            case StudentAdmissionService.AdmitNewStudentResult.EmailAlreadyRegistered ignored ->
                ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse("an account with this email already exists"));
            case StudentAdmissionService.AdmitNewStudentResult.DuplicateAdmissionNo ignored ->
                ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse("admission number already in use"));
        };
    }
}
