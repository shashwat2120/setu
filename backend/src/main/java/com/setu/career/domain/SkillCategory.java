package com.setu.career.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.util.UUID;

/** Maps career.skill_categories (V45) — {@code (tenant_id, code)} unique. */
@Entity
@Table(name = "skill_categories", schema = "career")
public class SkillCategory {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private String code;

    @Column(nullable = false)
    private String name;

    @Version
    private int version;

    protected SkillCategory() {
    }

    public SkillCategory(UUID id, UUID tenantId, String code, String name) {
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

    public int version() {
        return version;
    }

    public void update(String code, String name) {
        if (code == null || code.isBlank()) throw new IllegalArgumentException("code must not be blank");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name must not be blank");
        this.code = code.trim();
        this.name = name.trim();
    }
}
