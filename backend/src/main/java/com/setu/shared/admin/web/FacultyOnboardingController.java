package com.setu.shared.admin.web;

import com.setu.shared.admin.FacultyOnboardingService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/admin/faculty")
public class FacultyOnboardingController {
    private final FacultyOnboardingService onboarding;
    public FacultyOnboardingController(FacultyOnboardingService onboarding) { this.onboarding = onboarding; }
    public record Request(UUID tenantId, String fullName, LocalDate dob, String email, String phone,
                          String employeeNo, String designation, LocalDate joinedAt) {}
    @PostMapping
    public ResponseEntity<?> create(@RequestBody Request r) {
        return onboarding.onboard(r.tenantId(), r.fullName(), r.dob(), r.email(), r.phone(), r.employeeNo(), r.designation(), r.joinedAt())
            .<ResponseEntity<?>>map(result -> ResponseEntity.status(HttpStatus.CREATED).body(result))
            .orElseGet(() -> ResponseEntity.status(HttpStatus.CONFLICT).body(java.util.Map.of("message", "an account with this email already exists")));
    }
}
