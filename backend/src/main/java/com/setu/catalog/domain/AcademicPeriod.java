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

/**
 * Maps catalog.academic_periods (V18). Deliberately no "semester" concept
 * baked in anywhere — {@code code}/{@code name} are free text so a college
 * can model semesters, trimesters, quarters, or any custom scheme (Day 5
 * instruction §8). Transitions per docs/state-machines.md #6: linear
 * PLANNED -&gt; REGISTRATION_OPEN -&gt; REGISTRATION_CLOSED -&gt; IN_PROGRESS -&gt;
 * CLOSED, manual override of the automatic schedule allowed (audited by the
 * caller, AcademicPeriodService).
 */
@Entity
@Table(name = "academic_periods", schema = "catalog")
public class AcademicPeriod {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "academic_year_id", nullable = false)
    private UUID academicYearId;

    @Column(nullable = false)
    private String code;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private int sequence;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Column(name = "registration_open_at")
    private Instant registrationOpenAt;

    @Column(name = "registration_close_at")
    private Instant registrationCloseAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PeriodStatus status;

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

    protected AcademicPeriod() {
    }

    public AcademicPeriod(UUID id, UUID tenantId, UUID academicYearId, String code, String name, int sequence,
                           LocalDate startDate, LocalDate endDate, Instant registrationOpenAt,
                           Instant registrationCloseAt, UUID actorUserId, Instant now) {
        if (!endDate.isAfter(startDate)) {
            throw new IllegalArgumentException("Academic period end_date must be after start_date");
        }
        if (registrationOpenAt != null && registrationCloseAt != null
            && !registrationCloseAt.isAfter(registrationOpenAt)) {
            throw new IllegalArgumentException("registration_close_at must be after registration_open_at");
        }
        this.id = id;
        this.tenantId = tenantId;
        this.academicYearId = academicYearId;
        this.code = code;
        this.name = name;
        this.sequence = sequence;
        this.startDate = startDate;
        this.endDate = endDate;
        this.registrationOpenAt = registrationOpenAt;
        this.registrationCloseAt = registrationCloseAt;
        this.status = PeriodStatus.PLANNED;
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

    public UUID academicYearId() {
        return academicYearId;
    }

    public String code() {
        return code;
    }

    public String name() {
        return name;
    }

    public int sequence() {
        return sequence;
    }

    public LocalDate startDate() {
        return startDate;
    }

    public LocalDate endDate() {
        return endDate;
    }

    public PeriodStatus status() {
        return status;
    }

    public Instant registrationOpenAt() {
        return registrationOpenAt;
    }

    public Instant registrationCloseAt() {
        return registrationCloseAt;
    }

    /** Not "mid-term active" only when the period hasn't started (PLANNED) or has already fully wound down (CLOSED) — used by CollegeService's archive-validation (docs/state-machines.md #4). */
    public boolean isMidTermActive() {
        return status != PeriodStatus.PLANNED && status != PeriodStatus.CLOSED;
    }

    public boolean canTransitionTo(PeriodStatus target) {
        return switch (status) {
            case PLANNED -> target == PeriodStatus.REGISTRATION_OPEN;
            case REGISTRATION_OPEN -> target == PeriodStatus.REGISTRATION_CLOSED;
            case REGISTRATION_CLOSED -> target == PeriodStatus.IN_PROGRESS;
            case IN_PROGRESS -> target == PeriodStatus.CLOSED;
            case CLOSED -> false;
        };
    }

    public void transitionTo(PeriodStatus target, UUID actorUserId, Instant now) {
        if (!canTransitionTo(target)) {
            throw new IllegalStateException("Cannot transition academic period from " + status + " to " + target);
        }
        this.status = target;
        this.updatedAt = now;
        this.updatedBy = actorUserId;
    }

    public void updateRegistrationWindow(Instant openAt, Instant closeAt, UUID actorUserId, Instant now) {
        if (openAt != null && closeAt != null && !closeAt.isAfter(openAt)) {
            throw new IllegalArgumentException("registration_close_at must be after registration_open_at");
        }
        this.registrationOpenAt = openAt;
        this.registrationCloseAt = closeAt;
        this.updatedAt = now;
        this.updatedBy = actorUserId;
    }

    public enum PeriodStatus {PLANNED, REGISTRATION_OPEN, REGISTRATION_CLOSED, IN_PROGRESS, CLOSED}
}
