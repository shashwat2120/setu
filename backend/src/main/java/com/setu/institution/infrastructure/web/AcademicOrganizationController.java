package com.setu.institution.infrastructure.web;

import com.setu.institution.application.AcademicOrganizationService;
import com.setu.institution.domain.AcademicOrganization;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/institution/academic-organizations")
public class AcademicOrganizationController {

    private final AcademicOrganizationService organizationService;

    public AcademicOrganizationController(AcademicOrganizationService organizationService) {
        this.organizationService = organizationService;
    }

    public record CreateRequest(UUID campusId, UUID parentOrganizationId, String name, String type) {
    }

    public record OrganizationResponse(UUID id, UUID tenantId, UUID parentOrganizationId, String name) {
    }

    private static OrganizationResponse toResponse(AcademicOrganization o) {
        return new OrganizationResponse(o.id(), o.tenantId(), o.parentOrganizationId(), o.name());
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody CreateRequest request) {
        var result = organizationService.create(request.campusId(), request.parentOrganizationId(), request.name(), request.type());
        return switch (result) {
            case AcademicOrganizationService.CreateResult.Success success -> ResponseEntity.status(HttpStatus.CREATED)
                .body(organizationService.get(success.organizationId()).map(AcademicOrganizationController::toResponse).orElseThrow());
            case AcademicOrganizationService.CreateResult.CampusNotFound ignored ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("campus not found"));
            case AcademicOrganizationService.CreateResult.ParentNotFound ignored ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("parent organization not found"));
            case AcademicOrganizationService.CreateResult.ParentInDifferentTenant ignored ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("parent organization belongs to a different college"));
        };
    }

    @GetMapping("/{organizationId}")
    public ResponseEntity<?> get(@PathVariable UUID organizationId) {
        return organizationService.get(organizationId)
            .map(o -> ResponseEntity.ok(toResponse(o)))
            .orElse(ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }

    @GetMapping
    public ResponseEntity<List<OrganizationResponse>> listForTenant(@RequestParam UUID tenantId) {
        return ResponseEntity.ok(organizationService.listForTenant(tenantId).stream()
            .map(AcademicOrganizationController::toResponse).toList());
    }

    public record ReparentRequest(UUID newParentOrganizationId) {
    }

    @PostMapping("/{organizationId}/parent")
    public ResponseEntity<?> setParent(@PathVariable UUID organizationId, @RequestBody ReparentRequest request) {
        var result = organizationService.setParent(organizationId, request.newParentOrganizationId());
        return switch (result) {
            case AcademicOrganizationService.ReparentResult.Success ignored -> ResponseEntity.noContent().build();
            case AcademicOrganizationService.ReparentResult.NotFound ignored -> ResponseEntity.status(HttpStatus.NOT_FOUND).build();
            case AcademicOrganizationService.ReparentResult.ParentNotFound ignored ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("parent organization not found"));
            case AcademicOrganizationService.ReparentResult.SelfParenting ignored ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("an organization cannot be its own parent"));
            case AcademicOrganizationService.ReparentResult.ParentInDifferentTenant ignored ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("parent organization belongs to a different college"));
            case AcademicOrganizationService.ReparentResult.WouldCreateCycle ignored ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("this would create a cycle in the organization hierarchy"));
        };
    }

    public record ErrorResponse(String message) {
    }
}
