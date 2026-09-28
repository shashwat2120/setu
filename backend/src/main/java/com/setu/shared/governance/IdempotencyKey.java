package com.setu.shared.governance;

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
 * Maps governance.idempotency_keys (V4, extended V68) — see {@link
 * IdempotencyGuard}'s javadoc for the full claim/replay/conflict design
 * this entity backs. {@code (key, scope)} unique (V4); {@code
 * requestFingerprint}/{@code status}/{@code resultJson} added V68, Day 12,
 * the first real consumer of this table.
 */
@Entity
@Table(name = "idempotency_keys", schema = "governance")
public class IdempotencyKey {

    public enum Status {PENDING, COMPLETED}

    @Id
    private UUID id;

    @Column(nullable = false)
    private String key;

    @Column(nullable = false)
    private String scope;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "request_fingerprint")
    private String requestFingerprint;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "result_json")
    private String resultJson;

    protected IdempotencyKey() {
    }

    public IdempotencyKey(UUID id, String key, String scope, Instant createdAt, Instant expiresAt, String requestFingerprint) {
        this.id = id;
        this.key = key;
        this.scope = scope;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.requestFingerprint = requestFingerprint;
        this.status = Status.PENDING;
    }

    public UUID id() {
        return id;
    }

    public String key() {
        return key;
    }

    public String scope() {
        return scope;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    public String requestFingerprint() {
        return requestFingerprint;
    }

    public Status status() {
        return status;
    }

    public String resultJson() {
        return resultJson;
    }

    /** PENDING -&gt; COMPLETED only, storing the exact result a matching-fingerprint retry must replay. */
    public void complete(String resultJson) {
        if (status != Status.PENDING) {
            throw new IllegalStateException("cannot complete idempotency key from " + status);
        }
        this.status = Status.COMPLETED;
        this.resultJson = resultJson;
    }
}
