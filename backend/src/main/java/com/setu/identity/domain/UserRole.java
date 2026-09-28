package com.setu.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Maps identity.user_roles (V10) — a role grant to a user, tenant-scoped (tenant_id set) or platform-scoped (tenant_id null, SUPER_ADMIN only in practice). */
@Entity
@Table(name = "user_roles", schema = "identity")
public class UserRole {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "role_id", nullable = false)
    private UUID roleId;

    @Column(name = "tenant_id")
    private UUID tenantId;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Column(name = "ends_at")
    private Instant endsAt;

    protected UserRole() {
    }

    public UserRole(UUID id, UUID userId, UUID roleId, UUID tenantId, Instant startsAt, Instant endsAt) {
        this.id = id;
        this.userId = userId;
        this.roleId = roleId;
        this.tenantId = tenantId;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
    }

    public UUID id() {
        return id;
    }

    public UUID userId() {
        return userId;
    }

    public UUID roleId() {
        return roleId;
    }

    public UUID tenantId() {
        return tenantId;
    }

    public Instant endsAt() {
        return endsAt;
    }

    public boolean isActive(Instant now) {
        return !startsAt.isAfter(now) && (endsAt == null || endsAt.isAfter(now));
    }

    public void endNow(Instant now) {
        this.endsAt = now;
    }
}
