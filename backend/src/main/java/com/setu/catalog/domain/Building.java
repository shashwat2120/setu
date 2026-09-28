package com.setu.catalog.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/** Maps catalog.buildings (V24) — no audit/version columns exist on this table (unlike most others); governance.audit_logs still records mutations independently (see BuildingService), disclosed rather than silently assumed present. */
@Entity
@Table(name = "buildings", schema = "catalog")
public class Building {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private String code;

    @Column(nullable = false)
    private String name;

    protected Building() {
    }

    public Building(UUID id, UUID tenantId, String code, String name) {
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

    public void update(String name) {
        this.name = name;
    }
}
