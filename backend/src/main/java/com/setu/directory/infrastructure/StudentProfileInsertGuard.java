package com.setu.directory.infrastructure;

import com.setu.directory.domain.StudentAdmittedEvent;
import com.setu.directory.domain.StudentProfile;
import com.setu.shared.governance.OutboxEventWriter;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Isolates the racy duplicate-admission-number INSERT in its own {@code
 * REQUIRES_NEW} transaction — same PostgreSQL poisoned-transaction defense
 * as {@code AssessmentComponentInsertGuard} (see its javadoc for the full
 * explanation).
 * <p>
 * Added Sprint 5D hardening follow-up, with one deliberate difference from
 * every other guard in this codebase: {@code StudentProfileService}'s own
 * class javadoc documents that admission both persists the profile row and
 * writes the {@code StudentAdmitted} outbox event in the SAME transaction
 * "matching the canonical commit-before-publish design" — the entire point
 * of the transactional outbox pattern ({@link OutboxEventWriter}'s own
 * javadoc: "the business row and the outbox row commit together or not at
 * all"). Isolating only {@code saveAndFlush} into REQUIRES_NEW while
 * leaving the outbox write in the caller's outer transaction would fix the
 * poisoned-transaction bug while silently breaking that atomicity
 * guarantee — a strictly worse tradeoff. This guard therefore does both
 * writes together inside the same REQUIRES_NEW transaction, preserving
 * atomicity while still isolating the constraint-violation risk from the
 * caller's outer connection.
 */
@Component
public class StudentProfileInsertGuard {

    private final StudentProfileRepository studentProfileRepository;
    private final OutboxEventWriter outboxEventWriter;

    public StudentProfileInsertGuard(StudentProfileRepository studentProfileRepository, OutboxEventWriter outboxEventWriter) {
        this.studentProfileRepository = studentProfileRepository;
        this.outboxEventWriter = outboxEventWriter;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void insertWithOutboxEvent(StudentProfile profile, UUID tenantId, StudentAdmittedEvent event) {
        studentProfileRepository.saveAndFlush(profile);
        outboxEventWriter.write("student_profiles", profile.id(), event.eventType(), event.eventVersion(), event,
            tenantId, event.correlationId(), event.causationId());
    }
}
