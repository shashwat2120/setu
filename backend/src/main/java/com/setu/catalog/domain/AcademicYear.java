package com.setu.catalog.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Maps catalog.academic_years (V18). Transitions per docs/state-machines.md #5 — PLANNED -> CURRENT (only via AcademicYearService, which also closes any prior CURRENT year in the same transaction) -> CLOSED; no skipping PLANNED -> CLOSED directly, no reopening a CLOSED year. */
@Entity
@Table(name = "academic_years", schema = "catalog")
public class AcademicYear {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private String code;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private YearStatus status;

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

    protected AcademicYear() {
    }

    public AcademicYear(UUID id, UUID tenantId, String code, LocalDate startDate, LocalDate endDate,
                         UUID actorUserId, Instant now) {
        if (!endDate.isAfter(startDate)) {
            throw new IllegalArgumentException("Academic year end_date must be after start_date");
        }
        this.id = id;
        this.tenantId = tenantId;
        this.code = code;
        this.startDate = startDate;
        this.endDate = endDate;
        this.status = YearStatus.PLANNED;
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

    public String code() {
        return code;
    }

    public LocalDate startDate() {
        return startDate;
    }

    public LocalDate endDate() {
        return endDate;
    }

    public YearStatus status() {
        return status;
    }

    public boolean canTransitionTo(YearStatus target) {
        return switch (status) {
            case PLANNED -> target == YearStatus.CURRENT;
            case CURRENT -> target == YearStatus.CLOSED;
            case CLOSED -> false;
        };
    }

    public void markCurrent(UUID actorUserId, Instant now) {
        if (!canTransitionTo(YearStatus.CURRENT)) {
            throw new IllegalStateException("Cannot move academic year from " + status + " to CURRENT");
        }
        this.status = YearStatus.CURRENT;
        this.updatedAt = now;
        this.updatedBy = actorUserId;
    }

    /** Called on the PREVIOUSLY current year when a new one becomes current — a direct status flip, not through markCurrent/close's own transition rules, since PLANNED->CURRENT->CLOSED in one step is exactly what the state machine's "auto-close the old current year" rule requires. */
    public void forceCloseForSuccession(UUID actorUserId, Instant now) {
        this.status = YearStatus.CLOSED;
        this.updatedAt = now;
        this.updatedBy = actorUserId;
    }

    public void close(UUID actorUserId, Instant now) {
        if (!canTransitionTo(YearStatus.CLOSED)) {
            throw new IllegalStateException("Cannot close academic year from " + status);
        }
        this.status = YearStatus.CLOSED;
        this.updatedAt = now;
        this.updatedBy = actorUserId;
    }

    public enum YearStatus {PLANNED, CURRENT, CLOSED}
}
