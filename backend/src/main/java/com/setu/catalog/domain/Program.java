package com.setu.catalog.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Maps catalog.programs (V19, status CHECK added V58) — academic/catalog identity (a degree definition), NOT a cohort/class and NOT a course section (Day 6 §5). */
@Entity
@Table(name = "programs", schema = "catalog")
public class Program {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "department_id", nullable = false)
    private UUID departmentId;

    @Column(nullable = false)
    private String code;

    @Column(nullable = false)
    private String name;

    @Column(name = "degree_type")
    private String degreeType;

    @Column(name = "duration_terms")
    private Integer durationTerms;

    @Column(name = "credits_required")
    private BigDecimal creditsRequired;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ProgramStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by", nullable = false)
    private UUID updatedBy;

    @Version
    private int version;

    protected Program() {
    }

    public Program(UUID id, UUID tenantId, UUID departmentId, String code, String name, String degreeType,
                    Integer durationTerms, BigDecimal creditsRequired, UUID actorUserId, Instant now) {
        if (durationTerms != null && durationTerms <= 0) {
            throw new IllegalArgumentException("duration_terms must be positive");
        }
        if (creditsRequired != null && creditsRequired.signum() <= 0) {
            throw new IllegalArgumentException("credits_required must be positive");
        }
        this.id = id;
        this.tenantId = tenantId;
        this.departmentId = departmentId;
        this.code = code;
        this.name = name;
        this.degreeType = degreeType;
        this.durationTerms = durationTerms;
        this.creditsRequired = creditsRequired;
        this.status = ProgramStatus.ACTIVE;
        this.createdAt = now;
        this.createdBy = actorUserId;
        this.updatedAt = now;
        this.updatedBy = actorUserId;
    }

    public UUID id() {
        return id;
    }

    public UUID tenantId() {
        return tenantId;
    }

    public UUID departmentId() {
        return departmentId;
    }

    /** The length of the program in terms, or null if it was never set. */
    public Integer durationTerms() {
        return durationTerms;
    }

    public String code() {
        return code;
    }

    public String name() {
        return name;
    }

    public ProgramStatus status() {
        return status;
    }

    public void update(String name, String degreeType, Integer durationTerms, BigDecimal creditsRequired,
                        UUID actorUserId, Instant now) {
        if (durationTerms != null && durationTerms <= 0) {
            throw new IllegalArgumentException("duration_terms must be positive");
        }
        if (creditsRequired != null && creditsRequired.signum() <= 0) {
            throw new IllegalArgumentException("credits_required must be positive");
        }
        this.name = name;
        this.degreeType = degreeType;
        this.durationTerms = durationTerms;
        this.creditsRequired = creditsRequired;
        this.updatedAt = now;
        this.updatedBy = actorUserId;
    }

    public boolean canTransitionTo(ProgramStatus target) {
        return switch (status) {
            case ACTIVE -> target == ProgramStatus.INACTIVE;
            case INACTIVE -> target == ProgramStatus.ACTIVE;
        };
    }

    public void transitionTo(ProgramStatus target, UUID actorUserId, Instant now) {
        if (!canTransitionTo(target)) {
            throw new IllegalStateException("Cannot transition program from " + status + " to " + target);
        }
        this.status = target;
        this.updatedAt = now;
        this.updatedBy = actorUserId;
    }
}
