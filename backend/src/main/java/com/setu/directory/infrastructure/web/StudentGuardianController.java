package com.setu.directory.infrastructure.web;

import com.setu.directory.application.StudentGuardianService;
import com.setu.directory.domain.StudentGuardian;
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
@RequestMapping("/directory/student-guardians")
public class StudentGuardianController {

    private final StudentGuardianService studentGuardianService;

    public StudentGuardianController(StudentGuardianService studentGuardianService) {
        this.studentGuardianService = studentGuardianService;
    }

    public record LinkRequest(UUID studentId, UUID guardianId, String relationshipType, boolean primary, boolean emergencyContact) {
    }

    public record LinkResponse(UUID id, UUID studentId, UUID guardianId, String relationshipType, boolean primary) {
    }

    private static LinkResponse toResponse(StudentGuardian sg) {
        return new LinkResponse(sg.id(), sg.studentId(), sg.guardianId(), null, sg.isPrimary());
    }

    @PostMapping
    public ResponseEntity<?> link(@RequestBody LinkRequest request) {
        var result = studentGuardianService.link(request.studentId(), request.guardianId(), request.relationshipType(),
            request.primary(), request.emergencyContact());
        return switch (result) {
            case StudentGuardianService.LinkResult.Success success ->
                ResponseEntity.status(HttpStatus.CREATED).body(new LinkResponse(success.studentGuardianId(),
                    request.studentId(), request.guardianId(), request.relationshipType(), request.primary()));
            case StudentGuardianService.LinkResult.StudentNotFound ignored ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("student not found"));
            case StudentGuardianService.LinkResult.GuardianNotFound ignored ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("guardian not found"));
            case StudentGuardianService.LinkResult.TenantMismatch ignored ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("student and guardian belong to different colleges"));
            case StudentGuardianService.LinkResult.DuplicateRelationship ignored ->
                ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse("this relationship already exists"));
        };
    }

    @DeleteMapping("/{studentGuardianId}")
    public ResponseEntity<?> unlink(@PathVariable UUID studentGuardianId) {
        return switch (studentGuardianService.unlink(studentGuardianId)) {
            case SUCCESS -> ResponseEntity.noContent().build();
            case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        };
    }

    @GetMapping
    public ResponseEntity<List<LinkResponse>> forStudent(@org.springframework.web.bind.annotation.RequestParam UUID studentId) {
        return ResponseEntity.ok(studentGuardianService.guardiansForStudent(studentId).stream()
            .map(StudentGuardianController::toResponse).toList());
    }

    public record ErrorResponse(String message) {
    }
}
