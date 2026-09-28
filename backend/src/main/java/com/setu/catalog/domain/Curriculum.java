package com.setu.catalog.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Maps catalog.curricula (V20) — minimally, as a reference target for
 * student_programs.curriculum_id (Day 6 §5: "do not implement curricula yet
 * beyond what is strictly needed as a reference"). curriculum_requirements
 * and curriculum_requirement_courses (the actual degree-audit rules) are
 * genuinely Day 7+ scope and not touched here.
 */
@Entity
@Table(name = "curricula", schema = "catalog")
public class Curriculum {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "program_id", nullable = false)
    private UUID programId;

    @Column(nullable = false)
    private String name;

    @Column(name = "version_label", nullable = false)
    private String versionLabel;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

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

    protected Curriculum() {
    }

    public Curriculum(UUID id, UUID tenantId, UUID programId, String name, String versionLabel,
                       LocalDate effectiveFrom, LocalDate effectiveTo, UUID actorUserId, Instant now) {
        if (effectiveTo != null && !effectiveTo.isAfter(effectiveFrom)) {
            throw new IllegalArgumentException("effective_to must be after effective_from");
        }
        this.id = id;
        this.tenantId = tenantId;
        this.programId = programId;
        this.name = name;
        this.versionLabel = versionLabel;
        this.effectiveFrom = effectiveFrom;
        this.effectiveTo = effectiveTo;
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

    public UUID programId() {
        return programId;
    }

    public String name() {
        return name;
    }

    public String versionLabel() {
        return versionLabel;
    }

    public LocalDate effectiveFrom() {
        return effectiveFrom;
    }

    public LocalDate effectiveTo() {
        return effectiveTo;
    }

    /** Day 7 §2: "prevent invalid overlapping... effective-version semantics" — two curricula for the SAME program must not claim overlapping date ranges, so it's never ambiguous which version applies to a given date. */
    public boolean overlapsWith(LocalDate otherFrom, LocalDate otherTo) {
        boolean thisEndsBeforeOtherStarts = effectiveTo != null && !effectiveTo.isAfter(otherFrom);
        boolean otherEndsBeforeThisStarts = otherTo != null && !otherTo.isAfter(effectiveFrom);
        return !(thisEndsBeforeOtherStarts || otherEndsBeforeThisStarts);
    }

    /**
     * Only {@code name} and a forward-moving {@code effectiveTo} (closing an
     * open-ended version once its successor exists) may change — never
     * {@code effectiveFrom} or {@code programId}, and never backward, so a
     * historical version's window can't be destructively rewritten (Day 7
     * §2: "do not overwrite historical versions destructively").
     */
    public void update(String name, LocalDate newEffectiveTo, UUID actorUserId, Instant now) {
        if (newEffectiveTo != null) {
            if (!newEffectiveTo.isAfter(effectiveFrom)) {
                throw new IllegalArgumentException("effective_to must be after effective_from");
            }
            if (this.effectiveTo != null && newEffectiveTo.isBefore(this.effectiveTo)) {
                throw new IllegalArgumentException("effective_to cannot move backward on an existing curriculum version");
            }
        }
        this.name = name;
        this.effectiveTo = newEffectiveTo;
        this.updatedAt = now;
        this.updatedBy = actorUserId;
    }
}
