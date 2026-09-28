package com.setu.shared.admin.web;

import com.setu.shared.admin.GuardianOnboardingService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/admin/guardians")
public class GuardianOnboardingController {
    private final GuardianOnboardingService onboarding;
    public GuardianOnboardingController(GuardianOnboardingService onboarding) { this.onboarding = onboarding; }

    public record Request(UUID studentId, String fullName, LocalDate dob, String email, String phone,
                          String relationshipType, boolean primary, boolean emergencyContact) {}
    public record Created(UUID userId, UUID personId, UUID guardianId, UUID relationshipId) {}
    public record Error(String message) {}

    @PostMapping
    public ResponseEntity<?> createAndLink(@RequestBody Request request) {
        return switch (onboarding.createAndLink(request.studentId(), request.fullName(), request.dob(),
                request.email(), request.phone(), request.relationshipType(), request.primary(), request.emergencyContact())) {
            case GuardianOnboardingService.Result.Created result -> ResponseEntity.status(HttpStatus.CREATED)
                .body(new Created(result.userId(), result.personId(), result.guardianId(), result.relationshipId()));
            case GuardianOnboardingService.Result.StudentNotFound ignored -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new Error("student not found"));
            case GuardianOnboardingService.Result.EmailAlreadyRegistered ignored -> ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new Error("an account with this email already exists"));
        };
    }
}
