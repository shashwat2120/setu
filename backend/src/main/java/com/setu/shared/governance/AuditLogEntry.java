package com.setu.shared.governance;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/** Maps governance.audit_logs (V4) exactly — append-only, never updated or deleted by application code. */
@Entity
@Table(name = "audit_logs", schema = "governance")
public class AuditLogEntry {

    @Id
    private UUID id;

    @Column(name = "tenant_id")
    private UUID tenantId;

    @Column(name = "actor_user_id")
    private UUID actorUserId;

    @Column(name = "action", nullable = false)
    private String action;

    @Column(name = "resource_type", nullable = false)
    private String resourceType;

    @Column(name = "resource_id")
    private UUID resourceId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "before_json", columnDefinition = "jsonb")
    private String beforeJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "after_json", columnDefinition = "jsonb")
    private String afterJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AuditLogEntry() {
    }

    public AuditLogEntry(UUID id, UUID tenantId, UUID actorUserId, String action, String resourceType,
                          UUID resourceId, String beforeJson, String afterJson, Instant createdAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.actorUserId = actorUserId;
        this.action = action;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.beforeJson = beforeJson;
        this.afterJson = afterJson;
        this.createdAt = createdAt;
    }

    // Day 16 §super-admin: read accessors added for AuditLogReadService — this row was write-only (via
    // AuditLogWriter) until now, since no caller ever needed to read one back.
    public UUID id() {
        return id;
    }

    public UUID tenantId() {
        return tenantId;
    }

    public UUID actorUserId() {
        return actorUserId;
    }

    public String action() {
        return action;
    }

    public String resourceType() {
        return resourceType;
    }

    public UUID resourceId() {
        return resourceId;
    }

    public String afterJson() {
        return afterJson;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
