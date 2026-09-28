package com.setu.finance.infrastructure.web;

import com.setu.finance.application.FeeStructureService;
import com.setu.finance.domain.FeeStructure;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Day 12 §Finance: fee_structures — see FeeStructureService's javadoc. */
@RestController
@RequestMapping("/finance/fee-structures")
public class FeeStructureController {

    private final FeeStructureService feeStructureService;

    public FeeStructureController(FeeStructureService feeStructureService) {
        this.feeStructureService = feeStructureService;
    }

    public record CreateRequest(UUID tenantId, UUID programId, String name, UUID academicYearId) {
    }

    public record FeeStructureResponse(UUID id, UUID tenantId, UUID programId, String name, UUID academicYearId, String status) {
    }

    private static FeeStructureResponse toResponse(FeeStructure s) {
        return new FeeStructureResponse(s.id(), s.tenantId(), s.programId(), s.name(), s.academicYearId(), s.status().name());
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody CreateRequest request) {
        var result = feeStructureService.create(request.tenantId(), request.programId(), request.name(), request.academicYearId());
        return switch (result) {
            case FeeStructureService.CreateResult.Success r -> ResponseEntity.status(HttpStatus.CREATED)
                .body(feeStructureService.get(r.feeStructureId()).map(FeeStructureController::toResponse).orElseThrow());
            case FeeStructureService.CreateResult.TenantNotFound ignored ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("tenant not found"));
            case FeeStructureService.CreateResult.InvalidData invalid ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse(invalid.message()));
        };
    }

    @GetMapping("/{feeStructureId}")
    public ResponseEntity<?> get(@PathVariable UUID feeStructureId) {
        return feeStructureService.get(feeStructureId).map(s -> ResponseEntity.ok(toResponse(s)))
            .orElse(ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }

    @GetMapping
    public ResponseEntity<List<FeeStructureResponse>> listForTenant(@RequestParam UUID tenantId) {
        return ResponseEntity.ok(feeStructureService.listForTenant(tenantId).stream().map(FeeStructureController::toResponse).toList());
    }

    @DeleteMapping("/{feeStructureId}")
    public ResponseEntity<?> deactivate(@PathVariable UUID feeStructureId) {
        return switch (feeStructureService.deactivate(feeStructureId)) {
            case SUCCESS -> ResponseEntity.noContent().build();
            case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        };
    }

    public record ErrorResponse(String message) {
    }
}
