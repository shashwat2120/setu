package com.setu.catalog.infrastructure.web;

import com.setu.catalog.application.ProgramService;
import com.setu.catalog.domain.Program;
import com.setu.catalog.domain.ProgramStatus;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/catalog/programs")
public class ProgramController {

    private final ProgramService programService;

    public ProgramController(ProgramService programService) {
        this.programService = programService;
    }

    public record CreateRequest(UUID departmentId, String code, String name, String degreeType,
                                 Integer durationTerms, BigDecimal creditsRequired) {
    }

    public record ProgramResponse(UUID id, UUID tenantId, UUID departmentId, String code, String name, String status) {
    }

    private static ProgramResponse toResponse(Program p) {
        return new ProgramResponse(p.id(), p.tenantId(), p.departmentId(), p.code(), p.name(), p.status().name());
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody CreateRequest request) {
        var result = programService.create(request.departmentId(), request.code(), request.name(), request.degreeType(),
            request.durationTerms(), request.creditsRequired());
        return switch (result) {
            case ProgramService.CreateResult.Success success -> ResponseEntity.status(HttpStatus.CREATED)
                .body(programService.get(success.programId()).map(ProgramController::toResponse).orElseThrow());
            case ProgramService.CreateResult.DepartmentNotFound ignored ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("department not found"));
            case ProgramService.CreateResult.CodeAlreadyInUse ignored ->
                ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse("program code already in use"));
            case ProgramService.CreateResult.InvalidProgramData invalid ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse(invalid.message()));
        };
    }

    @GetMapping("/{programId}")
    public ResponseEntity<?> get(@PathVariable UUID programId) {
        return programService.get(programId).map(p -> ResponseEntity.ok(toResponse(p)))
            .orElse(ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }

    @GetMapping
    public ResponseEntity<List<ProgramResponse>> listForTenant(@RequestParam UUID tenantId) {
        return ResponseEntity.ok(programService.listForTenant(tenantId).stream().map(ProgramController::toResponse).toList());
    }

    public record UpdateRequest(String name, String degreeType, Integer durationTerms, BigDecimal creditsRequired) {
    }

    @PutMapping("/{programId}")
    public ResponseEntity<?> update(@PathVariable UUID programId, @RequestBody UpdateRequest request) {
        return switch (programService.update(programId, request.name(), request.degreeType(), request.durationTerms(), request.creditsRequired())) {
            case SUCCESS -> ResponseEntity.ok(programService.get(programId).map(ProgramController::toResponse).orElseThrow());
            case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).build();
            case INVALID_DATA -> ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("invalid program data"));
        };
    }

    public record StatusChangeRequest(ProgramStatus status) {
    }

    @PostMapping("/{programId}/status")
    public ResponseEntity<?> changeStatus(@PathVariable UUID programId, @RequestBody StatusChangeRequest request) {
        return switch (programService.changeStatus(programId, request.status())) {
            case SUCCESS -> ResponseEntity.noContent().build();
            case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).build();
            case INVALID_TRANSITION -> ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("invalid status transition"));
        };
    }

    public record ErrorResponse(String message) {
    }
}
