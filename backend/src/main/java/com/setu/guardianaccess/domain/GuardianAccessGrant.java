package com.setu.guardianaccess.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

/**
 * Maps guardian_access.guardian_access_grants (V16). A grant alone is NOT
 * academic access — it only becomes access once paired with a specific
 * {@link GuardianAccessPermission} row (Day 4 instruction §5). Distinct from
 * {@code directory.student_guardians}, which records the family
 * relationship itself and carries no access implication at all.
 */
@Entity
@Table(name = "guardian_access_grants", schema = "guardian_access")
public class GuardianAccessGrant {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "student_id", nullable = false)
    private UUID studentId;

    @Column(name = "guardian_id", nullable = false)
    private UUID guardianId;

    @Column(name = "granted_by", nullable = false)
    private UUID grantedBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private GrantStatus status;

    @Column(name = "granted_at", nullable = false)
    private Instant grantedAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Version
    private int version;

    protected GuardianAccessGrant() {
    }

    public GuardianAccessGrant(UUID id, UUID tenantId, UUID studentId, UUID guardianId, UUID grantedBy,
                                Instant expiresAt, Instant now) {
        this.id = id;
        this.tenantId = tenantId;
        this.studentId = studentId;
        this.guardianId = guardianId;
        this.grantedBy = grantedBy;
        this.status = GrantStatus.ACTIVE;
        this.grantedAt = now;
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

    public UUID guardianId() {
        return guardianId;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    public Instant revokedAt() {
        return revokedAt;
    }

    /** Active by both explicit status AND expiry — never trusts a background sweep having already flipped status to EXPIRED (fail-safe: a lapsed-but-unswept grant is still treated as inactive). */
    public boolean isUsable(Instant now) {
        boolean notExpired = expiresAt == null || expiresAt.isAfter(now);
        return status == GrantStatus.ACTIVE && notExpired;
    }

    public void revoke(Instant now) {
        this.status = GrantStatus.REVOKED;
        this.revokedAt = now;
    }

    public enum GrantStatus {ACTIVE, EXPIRED, REVOKED}
}
