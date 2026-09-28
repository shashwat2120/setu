package com.setu.catalog.infrastructure.web;

import com.setu.catalog.application.AcademicPeriodService;
import com.setu.catalog.domain.AcademicPeriod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/catalog/academic-periods")
public class AcademicPeriodController {

    private final AcademicPeriodService academicPeriodService;

    public AcademicPeriodController(AcademicPeriodService academicPeriodService) {
        this.academicPeriodService = academicPeriodService;
    }

    public record CreateRequest(UUID academicYearId, String code, String name, int sequence, LocalDate startDate,
                                 LocalDate endDate, Instant registrationOpenAt, Instant registrationCloseAt) {
    }

    public record PeriodResponse(UUID id, UUID tenantId, UUID academicYearId, String code, String name, int sequence,
                                  LocalDate startDate, LocalDate endDate, Instant registrationOpenAt,
                                  Instant registrationCloseAt, String status) {
    }

    private static PeriodResponse toResponse(AcademicPeriod p) {
        return new PeriodResponse(p.id(), p.tenantId(), p.academicYearId(), p.code(), p.name(), p.sequence(),
            p.startDate(), p.endDate(), p.registrationOpenAt(), p.registrationCloseAt(), p.status().name());
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody CreateRequest request) {
        var result = academicPeriodService.createPeriod(request.academicYearId(), request.code(), request.name(),
            request.sequence(), request.startDate(), request.endDate(), request.registrationOpenAt(), request.registrationCloseAt());
        return switch (result) {
            case AcademicPeriodService.CreateResult.Success success -> ResponseEntity.status(HttpStatus.CREATED)
                .body(academicPeriodService.getPeriod(success.periodId()).map(AcademicPeriodController::toResponse).orElseThrow());
            case AcademicPeriodService.CreateResult.AcademicYearNotFound ignored ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("academic year not found"));
            case AcademicPeriodService.CreateResult.OutsideAcademicYearBounds ignored ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("period dates fall outside the academic year"));
            case AcademicPeriodService.CreateResult.InvalidPeriodData invalid ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse(invalid.message()));
        };
    }

    @GetMapping("/{periodId}")
    public ResponseEntity<?> get(@PathVariable UUID periodId) {
        return academicPeriodService.getPeriod(periodId)
            .map(p -> ResponseEntity.ok(toResponse(p)))
            .orElse(ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }

    @GetMapping
    public ResponseEntity<List<PeriodResponse>> listForYear(@RequestParam UUID academicYearId) {
        return ResponseEntity.ok(academicPeriodService.listForYear(academicYearId).stream()
            .map(AcademicPeriodController::toResponse).toList());
    }

    public record TransitionRequest(AcademicPeriod.PeriodStatus target) {
    }

    @PostMapping("/{periodId}/status")
    public ResponseEntity<?> transition(@PathVariable UUID periodId, @RequestBody TransitionRequest request) {
        return switch (academicPeriodService.transitionTo(periodId, request.target())) {
            case SUCCESS -> ResponseEntity.noContent().build();
            case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).build();
            case INVALID_TRANSITION -> ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("invalid status transition"));
        };
    }

    public record RegistrationWindowRequest(Instant openAt, Instant closeAt) {
    }

    @PostMapping("/{periodId}/registration-window")
    public ResponseEntity<?> updateRegistrationWindow(@PathVariable UUID periodId, @RequestBody RegistrationWindowRequest request) {
        return switch (academicPeriodService.updateRegistrationWindow(periodId, request.openAt(), request.closeAt())) {
            case SUCCESS -> ResponseEntity.noContent().build();
            case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).build();
            case INVALID_WINDOW -> ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("registration close must be after registration open"));
        };
    }

    public record ErrorResponse(String message) {
    }
}
