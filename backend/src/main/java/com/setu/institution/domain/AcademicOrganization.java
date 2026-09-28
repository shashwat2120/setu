package com.setu.institution.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

/**
 * Maps institution.academic_organizations (V7, {@code parent_organization_id}
 * added V56 — see that migration's header). {@code type} is free text
 * (e.g. SCHOOL/FACULTY/INSTITUTE) rather than an enum — institutions vary
 * in how many organizational levels they use, and the canonical ERD left
 * it deliberately unconstrained.
 */
@Entity
@Table(name = "academic_organizations", schema = "institution")
public class AcademicOrganization {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "campus_id", nullable = false)
    private UUID campusId;

    @Column(name = "parent_organization_id")
    private UUID parentOrganizationId;

    @Column(nullable = false)
    private String name;

    private String type;

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

    protected AcademicOrganization() {
    }

    public AcademicOrganization(UUID id, UUID tenantId, UUID campusId, UUID parentOrganizationId, String name,
                                 String type, UUID actorUserId, Instant now) {
        this.id = id;
        this.tenantId = tenantId;
        this.campusId = campusId;
        this.parentOrganizationId = parentOrganizationId;
        this.name = name;
        this.type = type;
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

    public UUID parentOrganizationId() {
        return parentOrganizationId;
    }

    public String name() {
        return name;
    }

    public UUID campusId() {
        return campusId;
    }

    public void setParent(UUID parentOrganizationId, UUID actorUserId, Instant now) {
        this.parentOrganizationId = parentOrganizationId;
        this.updatedAt = now;
        this.updatedBy = actorUserId;
    }
}
