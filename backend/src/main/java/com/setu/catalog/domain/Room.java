package com.setu.catalog.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.UUID;

/** Maps catalog.rooms (V24). No audit/version columns exist on this table — see Building's javadoc for the same note. */
@Entity
@Table(name = "rooms", schema = "catalog")
public class Room {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "building_id", nullable = false)
    private UUID buildingId;

    @Column(nullable = false)
    private String code;

    @Column(nullable = false)
    private int capacity;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "features_json", columnDefinition = "jsonb")
    private String featuresJson;

    protected Room() {
    }

    public Room(UUID id, UUID tenantId, UUID buildingId, String code, int capacity, String featuresJson) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.id = id;
        this.tenantId = tenantId;
        this.buildingId = buildingId;
        this.code = code;
        this.capacity = capacity;
        this.featuresJson = featuresJson;
    }

    public UUID id() {
        return id;
    }

    public UUID tenantId() {
        return tenantId;
    }

    public UUID buildingId() {
        return buildingId;
    }

    public String code() {
        return code;
    }

    public int capacity() {
        return capacity;
    }

    public String featuresJson() {
        return featuresJson;
    }

    public void update(int capacity, String featuresJson) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
        this.featuresJson = featuresJson;
    }
}
