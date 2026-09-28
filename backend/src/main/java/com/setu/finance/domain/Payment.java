package com.setu.finance.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Maps finance.payments (V44) — state-machines.md #20 (Payment) exactly:
 * INITIATED -&gt; SUCCEEDED|FAILED, driven ONLY by processing a {@link
 * PaymentEvent} row, never by a client-reported status; SUCCEEDED -&gt;
 * REFUNDED (admin-initiated). FAILED and REFUNDED are terminal; SUCCEEDED
 * is terminal unless refunded. A failed payment is never retried in
 * place — a retry creates a brand-new {@code payments} row (state
 * machine's own "Correction" rule).
 */
@Entity
@Table(name = "payments", schema = "finance")
public class Payment {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "student_account_id", nullable = false)
    private UUID studentAccountId;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3)
    private String currency;

    private String method;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentStatus status;

    @Column(name = "provider_ref")
    private String providerRef;

    protected Payment() {
    }

    public Payment(UUID id, UUID tenantId, UUID studentAccountId, BigDecimal amount, String currency, String method) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("amount must be > 0");
        }
        if (currency == null || currency.length() != 3) {
            throw new IllegalArgumentException("currency must be a 3-letter ISO code");
        }
        this.id = id;
        this.tenantId = tenantId;
        this.studentAccountId = studentAccountId;
        this.amount = amount;
        this.currency = currency;
        this.method = method;
        this.status = PaymentStatus.INITIATED;
    }

    public UUID id() {
        return id;
    }

    public UUID tenantId() {
        return tenantId;
    }

    public UUID studentAccountId() {
        return studentAccountId;
    }

    public BigDecimal amount() {
        return amount;
    }

    public String currency() {
        return currency;
    }

    public String method() {
        return method;
    }

    public PaymentStatus status() {
        return status;
    }

    public String providerRef() {
        return providerRef;
    }

    public boolean canTransitionTo(PaymentStatus target) {
        return switch (status) {
            case INITIATED -> target == PaymentStatus.SUCCEEDED || target == PaymentStatus.FAILED;
            case SUCCEEDED -> target == PaymentStatus.REFUNDED;
            case FAILED, REFUNDED -> false;
        };
    }
}
