package com.setu.attendance.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

/**
 * Maps attendance.staff_location_policies (V95): one college's choice about recording where staff check in from. OFF unless an administrator turns it on,
 * and turning it on needs the college's campus position. The position here is the college's own, never a person's. Nothing in this policy ever
 * makes a check-in fail: see {@link StaffLocationPolicy#DEFAULT_RADIUS_METERS} and the service for what is, and is not, recorded.
 * The DB enforces the same rules (both coordinates or neither, enabled needs a position, radius 50 to 5000 m).
 */
@Entity
@Table(name = "staff_location_policies", schema = "attendance")
public class StaffLocationPolicy {

    public static final int DEFAULT_RADIUS_METERS = 300;
    public static final int MIN_RADIUS_METERS = 50;
    public static final int MAX_RADIUS_METERS = 5000;

    @Id
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "campus_latitude")
    private Double campusLatitude;

    @Column(name = "campus_longitude")
    private Double campusLongitude;

    @Column(name = "radius_meters", nullable = false)
    private int radiusMeters;

    @Column(name = "updated_by")
    private UUID updatedBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private Integer version;

    protected StaffLocationPolicy() {
    }

    public StaffLocationPolicy(UUID tenantId) {
        this.tenantId = tenantId;
        this.enabled = false;
        this.radiusMeters = DEFAULT_RADIUS_METERS;
        this.updatedAt = Instant.now();
    }

    /** The service validates first and answers with a message; this only refuses what the DB would refuse, so a bad state can never be built. */
    public void configure(boolean enabled, Double latitude, Double longitude, int radiusMeters, UUID by, Instant now) {
        if ((latitude == null) != (longitude == null)) {
            throw new IllegalArgumentException("a campus position is both latitude and longitude, or neither");
        }
        if (enabled && latitude == null) {
            throw new IllegalArgumentException("the campus position is needed to switch location on");
        }
        if (radiusMeters < MIN_RADIUS_METERS || radiusMeters > MAX_RADIUS_METERS) {
            throw new IllegalArgumentException("radius must be between " + MIN_RADIUS_METERS + " and " + MAX_RADIUS_METERS + " metres");
        }
        this.enabled = enabled;
        this.campusLatitude = latitude;
        this.campusLongitude = longitude;
        this.radiusMeters = radiusMeters;
        this.updatedBy = by;
        this.updatedAt = now;
    }

    public UUID tenantId() {
        return tenantId;
    }

    public boolean enabled() {
        return enabled;
    }

    public Double campusLatitude() {
        return campusLatitude;
    }

    public Double campusLongitude() {
        return campusLongitude;
    }

    public int radiusMeters() {
        return radiusMeters;
    }
}
