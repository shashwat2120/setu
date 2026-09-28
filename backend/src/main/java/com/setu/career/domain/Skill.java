package com.setu.career.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.util.UUID;

/**
 * Maps career.skills (V45) — {@code (tenant_id, code)} unique.
 * Tenant-scoped (not shared/global reference data): the ERD flags this as a
 * deliberate v1 design choice, not a contradiction — the default is
 * tenant-scoped for v1 consistency with everything else, to be revisited
 * only if cross-tenant skill taxonomy sharing becomes a real requirement.
 */
@Entity
@Table(name = "skills", schema = "career")
public class Skill {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private String code;

    @Column(nullable = false)
    private String name;

    @Column(name = "category_id")
    private UUID categoryId;

    @Version
    private int version;

    protected Skill() {
    }

    public Skill(UUID id, UUID tenantId, String code, String name, UUID categoryId) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("code must not be blank");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        this.id = id;
        this.tenantId = tenantId;
        this.code = code;
        this.name = name;
        this.categoryId = categoryId;
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

    public UUID categoryId() {
        return categoryId;
    }

    public int version() {
        return version;
    }

    public void update(String code, String name, UUID categoryId) {
        if (code == null || code.isBlank()) throw new IllegalArgumentException("code must not be blank");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name must not be blank");
        this.code = code.trim();
        this.name = name.trim();
        this.categoryId = categoryId;
    }
}
