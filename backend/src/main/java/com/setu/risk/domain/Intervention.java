package com.setu.risk.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Maps risk.interventions (V48) — state-machines.md #23 exactly: PLANNED
 * -&gt; IN_PROGRESS -&gt; COMPLETED|CANCELLED, each transition by the
 * assigned {@code ownerUserId} (the advisor) or COLLEGE_ADMIN. COMPLETED
 * requires at least one {@link InterventionAction} row documenting the
 * outcome — validated at the service layer (needs a repository count
 * check, not expressible as a pure entity invariant). A completed
 * intervention may be reopened ({@code COMPLETED -&gt; IN_PROGRESS}) by
 * COLLEGE_ADMIN only, audited.
 */
@Entity
@Table(name = "interventions", schema = "risk")
public class Intervention {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "student_id", nullable = false)
    private UUID studentId;

    @Column(name = "risk_score_id", nullable = false)
    private UUID riskScoreId;

    @Column(name = "recommendation_id")
    private UUID recommendationId;

    @Column(name = "owner_user_id", nullable = false)
    private UUID ownerUserId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private InterventionStatus status;

    @Column(name = "due_date")
    private LocalDate dueDate;

    protected Intervention() {
    }

    public Intervention(UUID id, UUID tenantId, UUID studentId, UUID riskScoreId, UUID recommendationId,
                         UUID ownerUserId, LocalDate dueDate) {
        this.id = id;
        this.tenantId = tenantId;
        this.studentId = studentId;
        this.riskScoreId = riskScoreId;
        this.recommendationId = recommendationId;
        this.ownerUserId = ownerUserId;
        this.status = InterventionStatus.PLANNED;
        this.dueDate = dueDate;
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

    public UUID riskScoreId() {
        return riskScoreId;
    }

    public UUID recommendationId() {
        return recommendationId;
    }

    public UUID ownerUserId() {
        return ownerUserId;
    }

    public InterventionStatus status() {
        return status;
    }

    public LocalDate dueDate() {
        return dueDate;
    }

    /** Pure state-shape check — the COMPLETED-requires-an-action rule lives in the service, which alone can query {@code intervention_actions}. */
    public boolean canTransitionTo(InterventionStatus target) {
        return switch (status) {
            case PLANNED -> target == InterventionStatus.IN_PROGRESS || target == InterventionStatus.CANCELLED;
            case IN_PROGRESS -> target == InterventionStatus.COMPLETED || target == InterventionStatus.CANCELLED;
            case COMPLETED -> target == InterventionStatus.IN_PROGRESS; // reopen, COLLEGE_ADMIN only — enforced by the service
            case CANCELLED -> false;
        };
    }

    public void transitionTo(InterventionStatus target) {
        if (!canTransitionTo(target)) {
            throw new IllegalStateException("cannot transition intervention from " + status + " to " + target);
        }
        this.status = target;
    }
}
