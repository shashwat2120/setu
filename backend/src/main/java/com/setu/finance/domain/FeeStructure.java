package com.setu.finance.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/** Maps finance.fee_structures (V43). Defines what CAN be charged for a program/academic-year, never a charge itself — {@link StudentCharge} is the actual obligation. */
@Entity
@Table(name = "fee_structures", schema = "finance")
public class FeeStructure {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "program_id")
    private UUID programId;

    @Column(nullable = false)
    private String name;

    @Column(name = "academic_year_id")
    private UUID academicYearId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private FeeStructureStatus status;

    protected FeeStructure() {
    }

    public FeeStructure(UUID id, UUID tenantId, UUID programId, String name, UUID academicYearId) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        this.id = id;
        this.tenantId = tenantId;
        this.programId = programId;
        this.name = name;
        this.academicYearId = academicYearId;
        this.status = FeeStructureStatus.ACTIVE;
    }

    public UUID id() {
        return id;
    }

    public UUID tenantId() {
        return tenantId;
    }

    public UUID programId() {
        return programId;
    }

    public String name() {
        return name;
    }

    public UUID academicYearId() {
        return academicYearId;
    }

    public FeeStructureStatus status() {
        return status;
    }

    public void setInactive() {
        this.status = FeeStructureStatus.INACTIVE;
    }
}
