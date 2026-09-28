package com.setu.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * Maps identity.sessions (V11) exactly. One row per refresh token, not per
 * "login" — rotation replaces a row rather than mutating it (see
 * docs/transaction-concurrency-matrix.md scenario 7). {@code familyId} is
 * shared across every row descended from one original login, so "revoke the
 * whole family" (reuse-detected compromise) is one query.
 */
@Entity
@Table(name = "sessions", schema = "identity")
public class Session {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "family_id", nullable = false)
    private UUID familyId;

    @Column(name = "refresh_token_hash", nullable = false)
    private String refreshTokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "device_metadata", columnDefinition = "jsonb")
    private String deviceMetadata;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Session() {
    }

    /** A brand-new login: starts a new family. */
    public static Session startNewFamily(UUID id, UUID userId, String refreshTokenHash, Instant expiresAt,
                                          String deviceMetadata, Instant now) {
        return new Session(id, userId, UUID.randomUUID(), refreshTokenHash, expiresAt, deviceMetadata, now);
    }

    /** A rotation: continues an existing family. */
    public static Session rotateWithinFamily(UUID id, UUID userId, UUID familyId, String refreshTokenHash,
                                              Instant expiresAt, String deviceMetadata, Instant now) {
        return new Session(id, userId, familyId, refreshTokenHash, expiresAt, deviceMetadata, now);
    }

    private Session(UUID id, UUID userId, UUID familyId, String refreshTokenHash, Instant expiresAt,
                     String deviceMetadata, Instant now) {
        this.id = id;
        this.userId = userId;
        this.familyId = familyId;
        this.refreshTokenHash = refreshTokenHash;
        this.expiresAt = expiresAt;
        this.deviceMetadata = deviceMetadata;
        this.createdAt = now;
    }

    public UUID id() {
        return id;
    }

    public UUID userId() {
        return userId;
    }

    public UUID familyId() {
        return familyId;
    }

    public boolean isExpired(Instant now) {
        return !expiresAt.isAfter(now);
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    public boolean isUsable(Instant now) {
        return !isExpired(now) && !isRevoked();
    }

    public void revoke(Instant now) {
        this.revokedAt = now;
    }
}
