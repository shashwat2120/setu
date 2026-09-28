package com.setu.enrollment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Maps enrollment.enrollment_overrides (V33). Always an explicit, approved
 * exception — never a silent bypass (canonical spec §3). No {@code version}
 * column exists on this table (schema fact, not omitted by mistake).
 * Transitions per docs/state-machines.md #13: created directly ACTIVE,
 * ACTIVE -&gt; USED|EXPIRED|REVOKED. A USED override cannot be reused.
 */
@Entity
@Table(name = "enrollment_overrides", schema = "enrollment")
public class EnrollmentOverride {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "student_id", nullable = false)
    private UUID studentId;

    @Column(name = "section_id", nullable = false)
    private UUID sectionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OverrideType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OverrideStatus status;

    @Column(nullable = false)
    private String reason;

    @Column(name = "approved_by", nullable = false)
    private UUID approvedBy;

    @Column(name = "expires_at")
    private Instant expiresAt;

    protected EnrollmentOverride() {
    }

    public EnrollmentOverride(UUID id, UUID tenantId, UUID studentId, UUID sectionId, OverrideType type,
                               String reason, UUID approvedBy, Instant expiresAt) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason is required");
        }
        this.id = id;
        this.tenantId = tenantId;
        this.studentId = studentId;
        this.sectionId = sectionId;
        this.type = type;
        this.status = OverrideStatus.ACTIVE;
        this.reason = reason;
        this.approvedBy = approvedBy;
        this.expiresAt = expiresAt;
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

    public UUID sectionId() {
        return sectionId;
    }

    public OverrideType type() {
        return type;
    }

    public OverrideStatus status() {
        return status;
    }

    public String reason() {
        return reason;
    }

    public UUID approvedBy() {
        return approvedBy;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    /** Live = ACTIVE and (no expiry or not yet expired) as of {@code now}. Consulted before an override is allowed to bypass a failed invariant-chain check. */
    public boolean isUsable(Instant now) {
        return status == OverrideStatus.ACTIVE && (expiresAt == null || expiresAt.isAfter(now));
    }

    public boolean canTransitionTo(OverrideStatus target) {
        return status == OverrideStatus.ACTIVE
            && (target == OverrideStatus.USED || target == OverrideStatus.EXPIRED || target == OverrideStatus.REVOKED);
    }

    public void markUsed() {
        if (!canTransitionTo(OverrideStatus.USED)) {
            throw new IllegalStateException("Cannot use override in status " + status);
        }
        this.status = OverrideStatus.USED;
    }

    public void markExpired() {
        if (!canTransitionTo(OverrideStatus.EXPIRED)) {
            throw new IllegalStateException("Cannot expire override in status " + status);
        }
        this.status = OverrideStatus.EXPIRED;
    }

    public void revoke() {
        if (!canTransitionTo(OverrideStatus.REVOKED)) {
            throw new IllegalStateException("Cannot revoke override in status " + status);
        }
        this.status = OverrideStatus.REVOKED;
    }
}
