package com.setu.institution.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * Maps institution.campuses (V7). {@code collegeId} and {@code tenantId}
 * are always equal by construction (the table's own CHECK constraint
 * enforces it at the DB level) — kept as two fields for join readability,
 * matching the ERD's own note on why the migration didn't drop one.
 */
@Entity
@Table(name = "campuses", schema = "institution")
public class Campus {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "college_id", nullable = false)
    private UUID collegeId;

    @Column(nullable = false)
    private String code;

    @Column(nullable = false)
    private String name;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "address_json", columnDefinition = "jsonb")
    private String addressJson;

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

    protected Campus() {
    }

    public Campus(UUID id, UUID collegeId, String code, String name, UUID actorUserId, Instant now) {
        this.id = id;
        this.tenantId = collegeId;
        this.collegeId = collegeId;
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

    public String code() {
        return code;
    }

    public String name() {
        return name;
    }

    public void update(String name, UUID actorUserId, Instant now) {
        this.name = name;
        this.updatedAt = now;
        this.updatedBy = actorUserId;
    }
}
