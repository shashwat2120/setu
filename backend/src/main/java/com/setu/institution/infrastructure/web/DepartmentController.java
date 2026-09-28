package com.setu.institution.infrastructure.web;

import com.setu.institution.application.DepartmentService;
import com.setu.institution.domain.Department;
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
@RequestMapping("/institution/departments")
public class DepartmentController {

    private final DepartmentService departmentService;

    public DepartmentController(DepartmentService departmentService) {
        this.departmentService = departmentService;
    }

    public record CreateRequest(UUID organizationId, String code, String name) {
    }

    public record DepartmentResponse(UUID id, UUID tenantId, UUID organizationId, String name, UUID hodUserId) {
    }

    private static DepartmentResponse toResponse(Department d) {
        return new DepartmentResponse(d.id(), d.tenantId(), d.organizationId(), d.name(), d.hodUserId());
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody CreateRequest request) {
        return switch (departmentService.create(request.organizationId(), request.code(), request.name())) {
            case DepartmentService.CreateResult.Success success -> ResponseEntity.status(HttpStatus.CREATED)
                .body(departmentService.get(success.departmentId()).map(DepartmentController::toResponse).orElseThrow());
            case DepartmentService.CreateResult.OrganizationNotFound ignored ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("academic organization not found"));
            case DepartmentService.CreateResult.CodeAlreadyInUse ignored ->
                ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse("department code already in use for this college"));
        };
    }

    @GetMapping("/{departmentId}")
    public ResponseEntity<?> get(@PathVariable UUID departmentId) {
        return departmentService.get(departmentId)
            .map(d -> ResponseEntity.ok(toResponse(d)))
            .orElse(ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }

    @GetMapping
    public ResponseEntity<List<DepartmentResponse>> listForTenant(@RequestParam UUID tenantId) {
        return ResponseEntity.ok(departmentService.listForTenant(tenantId).stream()
            .map(DepartmentController::toResponse).toList());
    }

    public record AssignHodRequest(UUID hodUserId) {
    }

    @PostMapping("/{departmentId}/hod")
    public ResponseEntity<?> assignHod(@PathVariable UUID departmentId, @RequestBody AssignHodRequest request) {
        var result = departmentService.assignHod(departmentId, request.hodUserId());
        return switch (result) {
            case DepartmentService.AssignHodResult.Success ignored -> ResponseEntity.noContent().build();
            case DepartmentService.AssignHodResult.NotFound ignored -> ResponseEntity.status(HttpStatus.NOT_FOUND).build();
            case DepartmentService.AssignHodResult.HodNotAuthorizedInTenant ignored -> ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("the proposed HOD has no active role in this college"));
        };
    }

    public record ErrorResponse(String message) {
    }
}
