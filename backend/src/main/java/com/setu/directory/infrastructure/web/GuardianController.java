package com.setu.directory.infrastructure.web;

import com.setu.directory.application.GuardianProfileService;
import com.setu.directory.domain.Guardian;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/directory/guardians")
public class GuardianController {

    private final GuardianProfileService guardianProfileService;

    public GuardianController(GuardianProfileService guardianProfileService) {
        this.guardianProfileService = guardianProfileService;
    }

    public record CreateRequest(UUID tenantId, UUID personId, String occupation, String organization, String addressJson) {
    }

    public record GuardianResponse(UUID id, UUID tenantId, UUID personId) {
    }

    private static GuardianResponse toResponse(Guardian g) {
        return new GuardianResponse(g.id(), g.tenantId(), g.personId());
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody CreateRequest request) {
        var result = guardianProfileService.create(request.tenantId(), request.personId(), request.occupation(),
            request.organization(), request.addressJson());
        return switch (result) {
            case GuardianProfileService.CreateResult.Success success -> ResponseEntity.status(HttpStatus.CREATED)
                .body(guardianProfileService.get(success.guardianId()).map(GuardianController::toResponse).orElseThrow());
            case GuardianProfileService.CreateResult.PersonNotFound ignored ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("person not found"));
            case GuardianProfileService.CreateResult.PersonAlreadyHasProfileInTenant ignored ->
                ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse("person already has a guardian profile in this college"));
        };
    }

    @GetMapping("/{guardianId}")
    public ResponseEntity<?> get(@PathVariable UUID guardianId) {
        return guardianProfileService.get(guardianId)
            .map(g -> ResponseEntity.ok(toResponse(g)))
            .orElse(ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }

    public record UpdateRequest(String occupation, String organization, String addressJson) {
    }

    @PutMapping("/{guardianId}")
    public ResponseEntity<?> update(@PathVariable UUID guardianId, @RequestBody UpdateRequest request) {
        return switch (guardianProfileService.update(guardianId, request.occupation(), request.organization(), request.addressJson())) {
            case SUCCESS -> ResponseEntity.ok(guardianProfileService.get(guardianId).map(GuardianController::toResponse).orElseThrow());
            case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        };
    }

    public record ErrorResponse(String message) {
    }
}
