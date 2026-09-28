package com.setu.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Maps identity.webauthn_challenges (V52) exactly — one row per issued WebAuthn ceremony challenge. */
@Entity
@Table(name = "webauthn_challenges", schema = "identity")
public class WebAuthnChallenge {

    @Id
    private UUID id;

    @Column(name = "user_id")
    private UUID userId;

    @Column(nullable = false)
    private byte[] challenge;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ChallengeType type;

    @Column(name = "rp_id", nullable = false)
    private String rpId;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    protected WebAuthnChallenge() {
    }

    public WebAuthnChallenge(UUID id, UUID userId, byte[] challenge, ChallengeType type, String rpId,
                              Instant expiresAt, Instant now) {
        this.id = id;
        this.userId = userId;
        this.challenge = challenge;
        this.type = type;
        this.rpId = rpId;
        this.expiresAt = expiresAt;
        this.createdAt = now;
    }

    public UUID id() {
        return id;
    }

    public UUID userId() {
        return userId;
    }

    public byte[] challenge() {
        return challenge;
    }

    public ChallengeType type() {
        return type;
    }

    public String rpId() {
        return rpId;
    }

    /** Unexpired AND not already used — a challenge is single-use by construction. */
    public boolean isUsable(Instant now) {
        return consumedAt == null && expiresAt.isAfter(now);
    }

    public void consume(Instant now) {
        this.consumedAt = now;
    }
}
