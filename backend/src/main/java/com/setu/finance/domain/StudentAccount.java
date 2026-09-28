package com.setu.finance.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Maps finance.student_accounts (V43) — one running balance per (tenant,
 * student), {@code (tenant_id, student_id)} unique. Per the ERD's own note,
 * {@code balance} is a derived/cached running total (charges owed minus
 * payments received; positive means the student owes money), never the
 * sole source of truth on its own — it must only ever be adjusted by an
 * atomic SQL increment ({@code UPDATE ... SET balance = balance + ?}, see
 * {@code StudentAccountRepository#adjustBalance}), never by a Java
 * read-modify-write of this entity, so concurrent charge/payment postings
 * can never lose an update.
 */
@Entity
@Table(name = "student_accounts", schema = "finance")
public class StudentAccount {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "student_id", nullable = false)
    private UUID studentId;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal balance;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3)
    private String currency;

    protected StudentAccount() {
    }

    public StudentAccount(UUID id, UUID tenantId, UUID studentId, String currency) {
        this.id = id;
        this.tenantId = tenantId;
        this.studentId = studentId;
        this.balance = BigDecimal.ZERO;
        this.currency = currency == null ? "INR" : currency;
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

    public BigDecimal balance() {
        return balance;
    }

    public String currency() {
        return currency;
    }
}
