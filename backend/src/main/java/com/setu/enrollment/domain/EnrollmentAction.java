package com.setu.enrollment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * Maps enrollment.enrollment_actions (V33) — append-only audit trail, no
 * update, no delete, ever (the table's own header comment). Every
 * state-changing {@link Enrollment} operation writes exactly one row here,
 * in the same transaction as the state change itself.
 */
@Entity
@Table(name = "enrollment_actions", schema = "enrollment")
public class EnrollmentAction {

    @Id
    private UUID id;

    @Column(name = "enrollment_id", nullable = false)
    private UUID enrollmentId;

    @Column(nullable = false)
    private String action;

    @Column(name = "actor_user_id", nullable = false)
    private UUID actorUserId;

    private String reason;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata_json", columnDefinition = "jsonb")
    private String metadataJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected EnrollmentAction() {
    }

    public EnrollmentAction(UUID id, UUID enrollmentId, String action, UUID actorUserId, String reason,
                             String metadataJson, Instant createdAt) {
        this.id = id;
        this.enrollmentId = enrollmentId;
        this.action = action;
        this.actorUserId = actorUserId;
        this.reason = reason;
        this.metadataJson = metadataJson;
        this.createdAt = createdAt;
    }

    public UUID id() {
        return id;
    }

    public UUID enrollmentId() {
        return enrollmentId;
    }

    public String action() {
        return action;
    }

    public UUID actorUserId() {
        return actorUserId;
    }

    public String reason() {
        return reason;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
