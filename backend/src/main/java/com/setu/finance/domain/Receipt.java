package com.setu.finance.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Maps finance.receipts (V44) — issued exactly once per SUCCEEDED payment; {@code (tenant_id, receipt_no)} unique. */
@Entity
@Table(name = "receipts", schema = "finance")
public class Receipt {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "payment_id", nullable = false)
    private UUID paymentId;

    @Column(name = "receipt_no", nullable = false)
    private String receiptNo;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    @Column(name = "document_ref")
    private String documentRef;

    protected Receipt() {
    }

    public Receipt(UUID id, UUID tenantId, UUID paymentId, String receiptNo, Instant issuedAt) {
        if (receiptNo == null || receiptNo.isBlank()) {
            throw new IllegalArgumentException("receiptNo must not be blank");
        }
        this.id = id;
        this.tenantId = tenantId;
        this.paymentId = paymentId;
        this.receiptNo = receiptNo;
        this.issuedAt = issuedAt;
    }

    public UUID id() {
        return id;
    }

    public UUID tenantId() {
        return tenantId;
    }

    public UUID paymentId() {
        return paymentId;
    }

    public String receiptNo() {
        return receiptNo;
    }

    public Instant issuedAt() {
        return issuedAt;
    }

    public String documentRef() {
        return documentRef;
    }
}
