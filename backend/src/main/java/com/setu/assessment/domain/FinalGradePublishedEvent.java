package com.setu.assessment.domain;

import com.setu.shared.events.DomainEvent;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A student's <b>final grade</b> for a course was published, or corrected: the published grade was for the section's component marked as its
 * final grade (a decision of the college: publishing a final grade completes the enrollment). Written, in the same transaction as the grade, in
 * addition to {@code GradePublished}, which still announces every published grade.
 * <p>
 * Keyed by the enrollment (the aggregate id is the enrollment id, not the grade id), so that the original publication and every correction of one
 * student's final grade share a Kafka key and arrive in the order they were made. The Academic service completes the enrollment from it
 * (docs/state-machines.md #11): the first one completes it, a correction changes its final status. {@code publishedBy} is the faculty member who
 * published the grade, the person on record for that change.
 */
public record FinalGradePublishedEvent(
    UUID eventId,
    Instant occurredAt,
    UUID tenantId,
    UUID aggregateId,
    UUID correlationId,
    UUID causationId,
    UUID gradeId,
    UUID enrollmentId,
    UUID componentId,
    int version,
    UUID supersedesId,
    String gradeCode,
    BigDecimal gradePoint,
    UUID publishedBy
) implements DomainEvent {

    @Override
    public String eventType() {
        return "FinalGradePublished";
    }

    @Override
    public int eventVersion() {
        return 1;
    }

    @Override
    public String producer() {
        return "assessment";
    }

    @Override
    public String aggregateType() {
        return "grades";
    }
}
