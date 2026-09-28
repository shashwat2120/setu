package com.setu.identity.infrastructure.web;

import com.setu.identity.application.UserRegistrationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/auth")
public class RegistrationController {

    private final UserRegistrationService registrationService;

    public RegistrationController(UserRegistrationService registrationService) {
        this.registrationService = registrationService;
    }

    public record RegisterRequest(@Email @NotBlank String email) {
    }

    public record RegisterResponse(UUID userId) {
    }

    @PostMapping("/register")
    public ResponseEntity<?> register(@Valid @RequestBody RegisterRequest request) {
        return switch (registrationService.register(request.email())) {
            case UserRegistrationService.Outcome.Created created ->
                ResponseEntity.status(HttpStatus.CREATED).body(new RegisterResponse(created.userId()));
            case UserRegistrationService.Outcome.EmailAlreadyRegistered ignored ->
                ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse("email already registered"));
        };
    }
}
