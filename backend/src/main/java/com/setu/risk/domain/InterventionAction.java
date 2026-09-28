package com.setu.risk.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Maps risk.intervention_actions (V48) — append-only audit trail, same pattern as enrollment_actions. Required at least once before an {@link Intervention} can move to COMPLETED. */
@Entity
@Table(name = "intervention_actions", schema = "risk")
public class InterventionAction {

    @Id
    private UUID id;

    @Column(name = "intervention_id", nullable = false)
    private UUID interventionId;

    @Column(nullable = false)
    private String action;

    @Column(name = "actor_user_id", nullable = false)
    private UUID actorUserId;

    private String notes;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected InterventionAction() {
    }

    public InterventionAction(UUID id, UUID interventionId, String action, UUID actorUserId, String notes, Instant createdAt) {
        if (action == null || action.isBlank()) {
            throw new IllegalArgumentException("action must not be blank");
        }
        this.id = id;
        this.interventionId = interventionId;
        this.action = action;
        this.actorUserId = actorUserId;
        this.notes = notes;
        this.createdAt = createdAt;
    }

    public UUID id() {
        return id;
    }

    public UUID interventionId() {
        return interventionId;
    }

    public String action() {
        return action;
    }

    public UUID actorUserId() {
        return actorUserId;
    }

    public String notes() {
        return notes;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
