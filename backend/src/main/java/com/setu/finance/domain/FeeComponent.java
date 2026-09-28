package com.setu.finance.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.util.UUID;

/** Maps finance.fee_components (V43). One named, priced line item ({@code code}, e.g. "TUITION") within a {@link FeeStructure}; {@code (fee_structure_id, code)} unique. */
@Entity
@Table(name = "fee_components", schema = "finance")
public class FeeComponent {

    @Id
    private UUID id;

    @Column(name = "fee_structure_id", nullable = false)
    private UUID feeStructureId;

    @Column(nullable = false)
    private String code;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3)
    private String currency;

    protected FeeComponent() {
    }

    public FeeComponent(UUID id, UUID feeStructureId, String code, BigDecimal amount, String currency) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("code must not be blank");
        }
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("amount must be > 0");
        }
        if (currency == null || currency.length() != 3) {
            throw new IllegalArgumentException("currency must be a 3-letter ISO code");
        }
        this.id = id;
        this.feeStructureId = feeStructureId;
        this.code = code;
        this.amount = amount;
        this.currency = currency;
    }

    public UUID id() {
        return id;
    }

    public UUID feeStructureId() {
        return feeStructureId;
    }

    public String code() {
        return code;
    }

    public BigDecimal amount() {
        return amount;
    }

    public String currency() {
        return currency;
    }
}
