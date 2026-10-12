package com.setu.directory.infrastructure.web;

import com.setu.directory.application.FacultyProfileService;
import com.setu.directory.domain.FacultyProfile;
import com.setu.directory.domain.FacultyStatus;
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

@RestController
@RequestMapping("/directory/faculty")
public class FacultyController {

    private final FacultyProfileService facultyProfileService;
    private final PersonDirectory personDirectory;

    public FacultyController(FacultyProfileService facultyProfileService, PersonDirectory personDirectory) {
        this.facultyProfileService = facultyProfileService;
        this.personDirectory = personDirectory;
    }

    public record CreateRequest(UUID tenantId, UUID personId, String employeeNo, String designation, LocalDate joinedAt) {
    }

    public record FacultyResponse(UUID id, UUID tenantId, UUID personId, UUID userId, String employeeNo, String status,
                                   String fullName, String email) {
    }

    private FacultyResponse toResponse(FacultyProfile f) {
        var person = personDirectory.findById(f.personId());
        String fullName = person.map(PersonDirectory.PersonSummary::legalName).orElse(null);
        String email = person.map(PersonDirectory.PersonSummary::email).orElse(null);
        // userId is exposed here because this whole endpoint is already the sanctioned "tenant-scoped profile"
        // path PersonController's own javadoc points admins to (never raw /identity/persons/{id}, which is
        // self-service-only) — needed so a college admin can assign this faculty member as a department HOD
        // (identity.RoleAssignmentController operates on userId, not facultyId).
        UUID userId = person.map(PersonDirectory.PersonSummary::userId).orElse(null);
        return new FacultyResponse(f.id(), f.tenantId(), f.personId(), userId, f.employeeNo(), f.status().name(), fullName, email);
    }

    public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {
    }

    @GetMapping
    public ResponseEntity<PageResponse<FacultyResponse>> list(@RequestParam UUID tenantId,
                                                                @RequestParam(defaultValue = "0") int page,
                                                                @RequestParam(defaultValue = "20") int size) {
        Page<FacultyProfile> result = facultyProfileService.listForTenant(tenantId, PageRequest.of(page, size));
        return ResponseEntity.ok(new PageResponse<>(result.getContent().stream().map(this::toResponse).toList(),
            result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages()));
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody CreateRequest request) {
        var result = facultyProfileService.create(request.tenantId(), request.personId(), request.employeeNo(),
            request.designation(), request.joinedAt());
        return switch (result) {
            case FacultyProfileService.CreateResult.Success success -> ResponseEntity.status(HttpStatus.CREATED)
                .body(facultyProfileService.get(success.facultyId()).map(this::toResponse).orElseThrow());
            case FacultyProfileService.CreateResult.PersonNotFound ignored ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("person not found"));
            case FacultyProfileService.CreateResult.PersonAlreadyHasProfileInTenant ignored ->
                ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse("person already has a faculty profile in this college"));
            case FacultyProfileService.CreateResult.DuplicateEmployeeNo ignored ->
                ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse("employee number already in use"));
        };
    }

    @GetMapping("/{facultyId}")
    public ResponseEntity<?> get(@PathVariable UUID facultyId) {
        return facultyProfileService.get(facultyId)
            .map(f -> ResponseEntity.ok(toResponse(f)))
            .orElse(ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }

    public record StatusChangeRequest(FacultyStatus status) {
    }

    @PostMapping("/{facultyId}/status")
    public ResponseEntity<?> changeStatus(@PathVariable UUID facultyId, @RequestBody StatusChangeRequest request) {
        return switch (facultyProfileService.changeStatus(facultyId, request.status())) {
            case SUCCESS -> ResponseEntity.noContent().build();
            case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).build();
            case INVALID_TRANSITION -> ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("invalid status transition"));
        };
    }

    public record ErrorResponse(String message) {
    }
}
