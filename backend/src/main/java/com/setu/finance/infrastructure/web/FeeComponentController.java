package com.setu.finance.infrastructure.web;

import com.setu.finance.application.FeeComponentService;
import com.setu.finance.domain.FeeComponent;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Day 12 §Finance: fee_components — see FeeComponentService's javadoc. */
@RestController
@RequestMapping("/finance/fee-components")
public class FeeComponentController {

    private final FeeComponentService feeComponentService;

    public FeeComponentController(FeeComponentService feeComponentService) {
        this.feeComponentService = feeComponentService;
    }

    public record CreateRequest(UUID feeStructureId, String code, BigDecimal amount, String currency) {
    }

    public record ComponentResponse(UUID id, UUID feeStructureId, String code, BigDecimal amount, String currency) {
    }

    private static ComponentResponse toResponse(FeeComponent c) {
        return new ComponentResponse(c.id(), c.feeStructureId(), c.code(), c.amount(), c.currency());
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody CreateRequest request) {
        var result = feeComponentService.create(request.feeStructureId(), request.code(), request.amount(), request.currency());
        return switch (result) {
            case FeeComponentService.CreateResult.Success r -> ResponseEntity.status(HttpStatus.CREATED)
                .body(toResponse(feeComponentService.listForStructure(request.feeStructureId()).stream()
                    .filter(c -> c.id().equals(r.componentId())).findFirst().orElseThrow()));
            case FeeComponentService.CreateResult.FeeStructureNotFound ignored ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("fee structure not found"));
            case FeeComponentService.CreateResult.InvalidData invalid ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse(invalid.message()));
            case FeeComponentService.CreateResult.CodeAlreadyInUse ignored ->
                ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse("a component already uses this code in this fee structure"));
        };
    }

    @GetMapping
    public ResponseEntity<List<ComponentResponse>> listForStructure(@RequestParam UUID feeStructureId) {
        return ResponseEntity.ok(feeComponentService.listForStructure(feeStructureId).stream().map(FeeComponentController::toResponse).toList());
    }

    public record ErrorResponse(String message) {
    }
}
