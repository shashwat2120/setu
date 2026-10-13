package com.setu.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Maps identity.password_reset_codes (V77). Only an HMAC of the code is ever stored. The row is created here and afterwards changed
 * only by the repository's atomic UPDATE statements (record an attempt, consume, invalidate), never by saving this entity again,
 * so the counters cannot be overwritten by a stale copy.
 */
@Entity
@Table(name = "password_reset_codes", schema = "identity")
public class PasswordResetCode {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "code_hash", nullable = false)
    private String codeHash;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected PasswordResetCode() {
    }

    public PasswordResetCode(UUID id, UUID userId, String codeHash, Instant expiresAt, Instant now) {
        this.id = id;
        this.userId = userId;
        this.codeHash = codeHash;
        this.attempts = 0;
        this.expiresAt = expiresAt;
        this.createdAt = now;
    }

    public UUID id() {
        return id;
    }

    public UUID userId() {
        return userId;
    }

    public String codeHash() {
        return codeHash;
    }

    public int attempts() {
        return attempts;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    public Instant usedAt() {
        return usedAt;
    }
}
