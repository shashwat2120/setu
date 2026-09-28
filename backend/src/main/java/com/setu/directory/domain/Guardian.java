package com.setu.directory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/** Maps directory.guardians (V14). One profile per person per tenant (enforced by the DB's own uq_guardians_tenant_person). A guardian profile is a domain record only — it confers no access by itself (Day 6 §7); see guardian_access_grants for that. */
@Entity
@Table(name = "guardians", schema = "directory")
public class Guardian {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "person_id", nullable = false)
    private UUID personId;

    private String occupation;

    private String organization;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "address_json", columnDefinition = "jsonb")
    private String addressJson;

    @Column(nullable = false)
    private String status;

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

    protected Guardian() {
    }

    /** Kept for the existing Day 4 bootstrap call path (DirectoryService.provisionGuardian) and its tests — equivalent to the richer constructor with every optional field null. */
    public Guardian(UUID id, UUID tenantId, UUID personId, UUID actorUserId, Instant now) {
        this(id, tenantId, personId, null, null, null, actorUserId, now);
    }

    public Guardian(UUID id, UUID tenantId, UUID personId, String occupation, String organization,
                     String addressJson, UUID actorUserId, Instant now) {
        this.id = id;
        this.tenantId = tenantId;
        this.personId = personId;
        this.occupation = occupation;
        this.organization = organization;
        this.addressJson = addressJson;
        this.status = "ACTIVE";
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

    public UUID personId() {
        return personId;
    }

    public String status() {
        return status;
    }

    public void update(String occupation, String organization, String addressJson, UUID actorUserId, Instant now) {
        this.occupation = occupation;
        this.organization = organization;
        this.addressJson = addressJson;
        this.updatedAt = now;
        this.updatedBy = actorUserId;
    }
}
