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

/**
 * Maps directory.student_guardians (V15) — the family RELATIONSHIP only.
 * Deliberately carries no access implication (Day 6 §7/§8): a row here
 * makes a guardian someone's family member on record, nothing more. Actual
 * access requires a separate guardian_access_grants row (guardianaccess
 * module) — the two are enforced completely independently, never coupled
 * in code.
 */
@Entity
@Table(name = "student_guardians", schema = "directory")
public class StudentGuardian {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "student_id", nullable = false)
    private UUID studentId;

    @Column(name = "guardian_id", nullable = false)
    private UUID guardianId;

    @Column(name = "relationship_type", nullable = false)
    private String relationshipType;

    @Column(name = "is_primary", nullable = false)
    private boolean primary;

    @Column(name = "is_emergency_contact", nullable = false)
    private boolean emergencyContact;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "notification_flags", columnDefinition = "jsonb", nullable = false)
    private String notificationFlags;

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

    protected StudentGuardian() {
    }

    public StudentGuardian(UUID id, UUID tenantId, UUID studentId, UUID guardianId, String relationshipType,
                            boolean primary, boolean emergencyContact, UUID actorUserId, Instant now) {
        this.id = id;
        this.tenantId = tenantId;
        this.studentId = studentId;
        this.guardianId = guardianId;
        this.relationshipType = relationshipType;
        this.primary = primary;
        this.emergencyContact = emergencyContact;
        this.notificationFlags = "{}";
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

    public UUID studentId() {
        return studentId;
    }

    public UUID guardianId() {
        return guardianId;
    }

    public boolean isPrimary() {
        return primary;
    }

    public void clearPrimary(UUID actorUserId, Instant now) {
        this.primary = false;
        this.updatedAt = now;
        this.updatedBy = actorUserId;
    }
}
