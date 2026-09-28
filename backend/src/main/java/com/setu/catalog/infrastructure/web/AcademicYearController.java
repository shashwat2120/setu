package com.setu.catalog.infrastructure.web;

import com.setu.catalog.application.AcademicYearService;
import com.setu.catalog.domain.AcademicYear;
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
@RequestMapping("/catalog/academic-years")
public class AcademicYearController {

    private final AcademicYearService academicYearService;

    public AcademicYearController(AcademicYearService academicYearService) {
        this.academicYearService = academicYearService;
    }

    public record CreateRequest(UUID tenantId, String code, LocalDate startDate, LocalDate endDate) {
    }

    public record YearResponse(UUID id, UUID tenantId, String code, LocalDate startDate, LocalDate endDate, String status) {
    }

    private static YearResponse toResponse(AcademicYear y) {
        return new YearResponse(y.id(), y.tenantId(), y.code(), y.startDate(), y.endDate(), y.status().name());
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody CreateRequest request) {
        try {
            UUID id = academicYearService.createYear(request.tenantId(), request.code(), request.startDate(), request.endDate());
            return ResponseEntity.status(HttpStatus.CREATED).body(academicYearService.getYear(id).map(AcademicYearController::toResponse).orElseThrow());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse(e.getMessage()));
        }
    }

    @GetMapping("/{yearId}")
    public ResponseEntity<?> get(@PathVariable UUID yearId) {
        return academicYearService.getYear(yearId)
            .map(y -> ResponseEntity.ok(toResponse(y)))
            .orElse(ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }

    @GetMapping
    public ResponseEntity<List<YearResponse>> listForTenant(@RequestParam UUID tenantId) {
        return ResponseEntity.ok(academicYearService.listForTenant(tenantId).stream().map(AcademicYearController::toResponse).toList());
    }

    @PostMapping("/{yearId}/mark-current")
    public ResponseEntity<?> markCurrent(@PathVariable UUID yearId) {
        return switch (academicYearService.markCurrent(yearId)) {
            case SUCCESS -> ResponseEntity.noContent().build();
            case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).build();
            case INVALID_TRANSITION -> ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("invalid status transition"));
        };
    }

    @PostMapping("/{yearId}/close")
    public ResponseEntity<?> close(@PathVariable UUID yearId) {
        return switch (academicYearService.close(yearId)) {
            case SUCCESS -> ResponseEntity.noContent().build();
            case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).build();
            case INVALID_TRANSITION -> ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("invalid status transition"));
            case OPEN_PERIODS_REMAIN -> ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse("all academic periods must be closed first"));
        };
    }

    public record ErrorResponse(String message) {
    }
}
