package com.setu.institution.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

/** Maps institution.departments (V7). {@code hodUserId} crosses into identity — validated by DepartmentService via identity's application port, never treated as a bare FK the way {@code organizationId} is (see V7's own header comment). */
@Entity
@Table(name = "departments", schema = "institution")
public class Department {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(nullable = false)
    private String code;

    @Column(nullable = false)
    private String name;

    @Column(name = "hod_user_id")
    private UUID hodUserId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by", nullable = false)
    private UUID updatedBy;

    @Version
    private int version;

    protected Department() {
    }

    public Department(UUID id, UUID tenantId, UUID organizationId, String code, String name, UUID actorUserId,
                       Instant now) {
        this.id = id;
        this.tenantId = tenantId;
        this.organizationId = organizationId;
        this.code = code;
        this.name = name;
        this.createdAt = now;
        this.createdBy = actorUserId;
        this.updatedAt = now;
        this.updatedBy = actorUserId;
    }

    public UUID id() {
        return id;
    }

    public UUID tenantId() {
        return tenantId;
    }

    public UUID organizationId() {
        return organizationId;
    }

    public String name() {
        return name;
    }

    public UUID hodUserId() {
        return hodUserId;
    }

    public void assignHod(UUID hodUserId, UUID actorUserId, Instant now) {
        this.hodUserId = hodUserId;
        this.updatedAt = now;
        this.updatedBy = actorUserId;
    }
}
