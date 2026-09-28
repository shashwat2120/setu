package com.setu.institution.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * Maps institution.colleges (V6). {@code id} IS the tenant_id used
 * everywhere else (docs/contradiction-audit.md #1). Sprint 5B Day 4 only
 * needs enough of this entity to satisfy FK-referencing tests and to let
 * RoleAssignmentService validate a tenant actually exists — full
 * institution-module CRUD (campuses, departments, settings) is out of
 * Day 4's authorization scope.
 */
@Entity
@Table(name = "colleges", schema = "institution")
public class College {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String code;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CollegeStatus status;

    @Column(nullable = false)
    private String timezone;

    @Column(nullable = false)
    private String locale;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "settings_json", columnDefinition = "jsonb", nullable = false)
    private String settingsJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private int version;

    protected College() {
    }

    public College(UUID id, String code, String name, String timezone, Instant now) {
        this.id = id;
        this.code = code;
        this.name = name;
        this.status = CollegeStatus.ACTIVE;
        this.timezone = timezone;
        this.locale = "en-IN";
        this.settingsJson = "{}";
        this.createdAt = now;
        this.updatedAt = now;
    }

    public UUID id() {
        return id;
    }

    public String code() {
        return code;
    }

    public String name() {
        return name;
    }

    public String timezone() {
        return timezone;
    }

    public String locale() {
        return locale;
    }

    public CollegeStatus status() {
        return status;
    }

    public String settingsJson() {
        return settingsJson;
    }

    public void update(String name, String timezone, String locale, Instant now) {
        this.name = name;
        this.timezone = timezone;
        this.locale = locale;
        this.updatedAt = now;
    }

    /**
     * A Day 5 gap closed in Day 6 (see the Day 6 report's carryover
     * section): settings_json had a column and a "{}" default but no
     * reader/writer anywhere. Needed now for the documented
     * {@code allow_dual_program} tenant policy toggle
     * (docs/erd/02-academic.md's student_programs section) — the raw JSON
     * string is stored as-is; CollegeService owns parsing specific keys out
     * of it, this entity doesn't know what any particular key means.
     */
    public void updateSettings(String settingsJson, Instant now) {
        this.settingsJson = settingsJson;
        this.updatedAt = now;
    }

    /**
     * docs/state-machines.md #4: ACTIVE&lt;-&gt;SUSPENDED both ways;
     * ACTIVE|SUSPENDED -&gt; ARCHIVED, terminal. No skipping straight into
     * ARCHIVED from a state that was never ACTIVE/SUSPENDED (there isn't
     * one), and no transition at all once ARCHIVED.
     */
    public boolean canTransitionTo(CollegeStatus target) {
        return switch (status) {
            case ACTIVE -> target == CollegeStatus.SUSPENDED || target == CollegeStatus.ARCHIVED;
            case SUSPENDED -> target == CollegeStatus.ACTIVE || target == CollegeStatus.ARCHIVED;
            case ARCHIVED -> false;
        };
    }

    public void transitionTo(CollegeStatus target, Instant now) {
        if (!canTransitionTo(target)) {
            throw new IllegalStateException("Cannot transition college from " + status + " to " + target);
        }
        this.status = target;
        this.updatedAt = now;
    }

    public enum CollegeStatus {ACTIVE, SUSPENDED, ARCHIVED}
}
