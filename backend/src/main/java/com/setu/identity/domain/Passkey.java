package com.setu.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * Maps identity.passkeys (V12) exactly. {@code publicKey} is the WebAuthn
 * credential's COSE public key — never a private key; WebAuthn authenticators
 * never disclose the private key to the relying party by design, so there is
 * nothing here to leak even in principle.
 */
@Entity
@Table(name = "passkeys", schema = "identity")
public class Passkey {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "credential_id", nullable = false)
    private byte[] credentialId;

    @Column(name = "public_key", nullable = false)
    private byte[] publicKey;

    @Column(name = "sign_count", nullable = false)
    private long signCount;

    @SuppressWarnings("unused") // mapped column: written by JPA, not read in Java yet
    private UUID aaguid;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "transports")
    private String[] transports;

    @Column(name = "backed_up", nullable = false)
    private boolean backedUp;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PasskeyStatus status;

    protected Passkey() {
    }

    public Passkey(UUID id, UUID userId, byte[] credentialId, byte[] publicKey, long signCount, UUID aaguid,
                    String[] transports, boolean backedUp, Instant now) {
        this.id = id;
        this.userId = userId;
        this.credentialId = credentialId;
        this.publicKey = publicKey;
        this.signCount = signCount;
        this.aaguid = aaguid;
        this.transports = transports;
        this.backedUp = backedUp;
        this.createdAt = now;
        this.status = PasskeyStatus.ACTIVE;
    }

    public UUID id() {
        return id;
    }

    public UUID userId() {
        return userId;
    }

    public byte[] credentialId() {
        return credentialId;
    }

    public byte[] publicKey() {
        return publicKey;
    }

    public long signCount() {
        return signCount;
    }

    public boolean isActive() {
        return status == PasskeyStatus.ACTIVE;
    }

    /**
     * WebAuthn semantics (§6.1.1): if the authenticator reports a sign_count
     * that isn't strictly greater than what we last stored (and isn't the
     * authenticator opting out of counting via a constant 0), that's evidence
     * of a cloned authenticator — the caller must treat this as a failed
     * authentication, not silently accept it.
     */
    public boolean isSignCountValid(long newSignCount) {
        if (newSignCount == 0 && this.signCount == 0) {
            return true; // authenticator doesn't support counting; both sides opted out, consistently
        }
        return newSignCount > this.signCount;
    }

    public void recordUse(long newSignCount, Instant now) {
        this.signCount = newSignCount;
        this.lastUsedAt = now;
    }

    public void revoke(Instant now) {
        this.status = PasskeyStatus.REVOKED;
        this.revokedAt = now;
    }
}
