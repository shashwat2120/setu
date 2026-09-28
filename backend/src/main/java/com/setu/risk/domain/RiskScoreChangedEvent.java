package com.setu.risk.domain;

import com.setu.shared.events.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/** Matches docs/kafka-event-catalog.md's {@code RiskScoreChanged} row: aggregate {@code risk_scores}, ordering key {@code student_id}, payload {@code risk_score_id, student_id, old_band|null, new_band, rule_version}. Emitted only when {@code band} actually changes from the previous current score — state-machines.md #21's own rule, to avoid alert fatigue. */
public record RiskScoreChangedEvent(
    UUID eventId,
    Instant occurredAt,
    UUID tenantId,
    UUID aggregateId,
    UUID correlationId,
    UUID causationId,
    UUID riskScoreId,
    UUID studentId,
    RiskBand oldBand,
    RiskBand newBand,
    String ruleVersion
) implements DomainEvent {

    @Override
    public String eventType() {
        return "RiskScoreChanged";
    }

    @Override
    public int eventVersion() {
        return 1;
    }

    @Override
    public String producer() {
        return "risk";
    }

    @Override
    public String aggregateType() {
        return "risk_scores";
    }
}
