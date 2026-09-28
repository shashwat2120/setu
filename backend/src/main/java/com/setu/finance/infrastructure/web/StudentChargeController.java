package com.setu.finance.infrastructure.web;

import com.setu.finance.application.StudentChargeService;
import com.setu.finance.domain.StudentCharge;
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

/** Day 12 §Finance: student_charges — see StudentChargeService's javadoc. */
@RestController
@RequestMapping("/finance/charges")
public class StudentChargeController {

    private final StudentChargeService studentChargeService;

    public StudentChargeController(StudentChargeService studentChargeService) {
        this.studentChargeService = studentChargeService;
    }

    public record CreateRequest(UUID studentId, UUID feeComponentId, LocalDate dueDate) {
    }

    public record ChargeResponse(UUID id, UUID tenantId, UUID studentId, UUID feeComponentId, java.math.BigDecimal amount,
                                  String currency, LocalDate dueDate, String status) {
    }

    private static ChargeResponse toResponse(StudentCharge c) {
        return new ChargeResponse(c.id(), c.tenantId(), c.studentId(), c.feeComponentId(), c.amount(), c.currency(), c.dueDate(), c.status().name());
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody CreateRequest request) {
        var result = studentChargeService.create(request.studentId(), request.feeComponentId(), request.dueDate());
        return switch (result) {
            case StudentChargeService.CreateResult.Success r -> ResponseEntity.status(HttpStatus.CREATED)
                .body(studentChargeService.listForStudent(request.studentId()).stream()
                    .filter(c -> c.id().equals(r.chargeId())).findFirst().map(StudentChargeController::toResponse).orElseThrow());
            case StudentChargeService.CreateResult.FeeComponentNotFound ignored ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("fee component not found"));
            case StudentChargeService.CreateResult.StudentNotFound ignored ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("student not found"));
        };
    }

    @GetMapping
    public ResponseEntity<List<ChargeResponse>> listForStudent(@RequestParam UUID studentId) {
        return ResponseEntity.ok(studentChargeService.listForStudent(studentId).stream().map(StudentChargeController::toResponse).toList());
    }

    private ResponseEntity<?> handleTransition(StudentChargeService.TransitionResult result) {
        return switch (result) {
            case StudentChargeService.TransitionResult.Success ignored -> ResponseEntity.noContent().build();
            case StudentChargeService.TransitionResult.NotFound ignored -> ResponseEntity.status(HttpStatus.NOT_FOUND).build();
            case StudentChargeService.TransitionResult.InvalidTransition ignored ->
                ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse("invalid status transition"));
        };
    }

    @PostMapping("/{chargeId}/waive")
    public ResponseEntity<?> waive(@PathVariable UUID chargeId) {
        return handleTransition(studentChargeService.markWaived(chargeId));
    }

    @PostMapping("/{chargeId}/overdue")
    public ResponseEntity<?> markOverdue(@PathVariable UUID chargeId) {
        return handleTransition(studentChargeService.markOverdue(chargeId));
    }

    @PostMapping("/{chargeId}/paid")
    public ResponseEntity<?> markPaid(@PathVariable UUID chargeId) {
        return handleTransition(studentChargeService.markPaid(chargeId));
    }

    public record ErrorResponse(String message) {
    }
}
