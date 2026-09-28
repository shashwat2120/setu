package com.setu.finance.infrastructure.web;

import com.setu.finance.application.ReceiptService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

/** Day 12 §Finance: receipts — see ReceiptService's javadoc. */
@RestController
@RequestMapping("/finance/receipts")
public class ReceiptController {

    private final ReceiptService receiptService;

    public ReceiptController(ReceiptService receiptService) {
        this.receiptService = receiptService;
    }

    public record ReceiptResponse(UUID id, UUID paymentId, String receiptNo, Instant issuedAt, String documentRef) {
    }

    @GetMapping("/by-payment/{paymentId}")
    public ResponseEntity<?> getForPayment(@PathVariable UUID paymentId) {
        return receiptService.getForPayment(paymentId)
            .map(r -> ResponseEntity.ok(new ReceiptResponse(r.id(), r.paymentId(), r.receiptNo(), r.issuedAt(), r.documentRef())))
            .orElse(ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }
}
