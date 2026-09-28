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
import java.time.LocalDate;
import java.util.UUID;

/**
 * Maps finance.student_charges (V43) — one student's actual obligation for
 * one {@link FeeComponent}. See {@link StudentChargeStatus}'s javadoc for
 * why the transition set below is this module's own minimal, disclosed
 * design (no canonical state-machines.md entry exists for this entity).
 * Amount/currency are copied from the fee component at charge-creation
 * time (never re-read live later) — a later fee-structure edit must never
 * retroactively change what a student already owes.
 */
@Entity
@Table(name = "student_charges", schema = "finance")
public class StudentCharge {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "student_id", nullable = false)
    private UUID studentId;

    @Column(name = "fee_component_id", nullable = false)
    private UUID feeComponentId;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private StudentChargeStatus status;

    protected StudentCharge() {
    }

    public StudentCharge(UUID id, UUID tenantId, UUID studentId, UUID feeComponentId, BigDecimal amount,
                          String currency, LocalDate dueDate) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("amount must be > 0");
        }
        if (currency == null || currency.length() != 3) {
            throw new IllegalArgumentException("currency must be a 3-letter ISO code");
        }
        this.id = id;
        this.tenantId = tenantId;
        this.studentId = studentId;
        this.feeComponentId = feeComponentId;
        this.amount = amount;
        this.currency = currency;
        this.dueDate = dueDate;
        this.status = StudentChargeStatus.PENDING;
    }

    public UUID id() {
        return id;
    }

    public UUID tenantId() {
        return tenantId;
    }

    public UUID studentId() {
        return studentId;
    }

    public UUID feeComponentId() {
        return feeComponentId;
    }

    public BigDecimal amount() {
        return amount;
    }

    public String currency() {
        return currency;
    }

    public LocalDate dueDate() {
        return dueDate;
    }

    public StudentChargeStatus status() {
        return status;
    }

    public boolean canTransitionTo(StudentChargeStatus target) {
        return switch (status) {
            case PENDING -> target == StudentChargeStatus.PAID || target == StudentChargeStatus.WAIVED
                || target == StudentChargeStatus.OVERDUE;
            case OVERDUE -> target == StudentChargeStatus.PAID || target == StudentChargeStatus.WAIVED;
            case PAID, WAIVED -> false;
        };
    }
}
