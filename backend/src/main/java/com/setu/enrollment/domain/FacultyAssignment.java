package com.setu.enrollment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Maps enrollment.faculty_assignments (V30). No docs/state-machines.md entry
 * exists for this entity — it has no status column at all, only
 * {@code ends_at} marking an assignment as no longer active (the DB's own
 * partial unique index, uq_faculty_assignments_one_active_primary, keys off
 * exactly that: {@code role = 'PRIMARY' AND ends_at IS NULL}). History is
 * preserved by ending an assignment (setting ends_at) rather than deleting
 * it, matching the same append-only-at-the-boundary pattern already
 * established for student_programs (Day 6).
 */
@Entity
@Table(name = "faculty_assignments", schema = "enrollment")
public class FacultyAssignment {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "section_id", nullable = false)
    private UUID sectionId;

    @Column(name = "faculty_id", nullable = false)
    private UUID facultyId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private FacultyAssignmentRole role;

    @Column(name = "allocation_pct")
    private BigDecimal allocationPct;

    @Column(name = "starts_at")
    private LocalDate startsAt;

    @Column(name = "ends_at")
    private LocalDate endsAt;

    protected FacultyAssignment() {
    }

    public FacultyAssignment(UUID id, UUID tenantId, UUID sectionId, UUID facultyId, FacultyAssignmentRole role,
                              BigDecimal allocationPct, LocalDate startsAt, LocalDate endsAt) {
        if (allocationPct != null
            && (allocationPct.signum() <= 0 || allocationPct.compareTo(new BigDecimal("100")) > 0)) {
            throw new IllegalArgumentException("allocation_pct must be between 0 (exclusive) and 100 (inclusive)");
        }
        if (startsAt != null && endsAt != null && endsAt.isBefore(startsAt)) {
            throw new IllegalArgumentException("ends_at cannot precede starts_at");
        }
        this.id = id;
        this.tenantId = tenantId;
        this.sectionId = sectionId;
        this.facultyId = facultyId;
        this.role = role;
        this.allocationPct = allocationPct;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
    }

    public UUID id() {
        return id;
    }

    public UUID tenantId() {
        return tenantId;
    }

    public UUID sectionId() {
        return sectionId;
    }

    public UUID facultyId() {
        return facultyId;
    }

    public FacultyAssignmentRole role() {
        return role;
    }

    public BigDecimal allocationPct() {
        return allocationPct;
    }

    public LocalDate startsAt() {
        return startsAt;
    }

    public LocalDate endsAt() {
        return endsAt;
    }

    public boolean isActive() {
        return endsAt == null;
    }

    /** Only allocationPct/startsAt may be corrected in place — role and section/faculty identity never change on an existing assignment; a role change is a new assignment (end this one, create another), preserving history per this class's javadoc. */
    public void updateAllocation(BigDecimal allocationPct, LocalDate startsAt) {
        if (allocationPct != null
            && (allocationPct.signum() <= 0 || allocationPct.compareTo(new BigDecimal("100")) > 0)) {
            throw new IllegalArgumentException("allocation_pct must be between 0 (exclusive) and 100 (inclusive)");
        }
        if (startsAt != null && endsAt != null && endsAt.isBefore(startsAt)) {
            throw new IllegalArgumentException("ends_at cannot precede starts_at");
        }
        this.allocationPct = allocationPct;
        this.startsAt = startsAt;
    }

    public void end(LocalDate endsAt) {
        if (startsAt != null && endsAt != null && endsAt.isBefore(startsAt)) {
            throw new IllegalArgumentException("ends_at cannot precede starts_at");
        }
        this.endsAt = endsAt;
    }
}
