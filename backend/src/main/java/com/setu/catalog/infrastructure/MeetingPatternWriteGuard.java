package com.setu.catalog.infrastructure;

import com.setu.catalog.domain.MeetingPattern;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Isolates the racy room/time-overlap-constrained WRITE (both the initial
 * INSERT and a later UPDATE that changes room/time) in its own {@code
 * REQUIRES_NEW} transaction — same PostgreSQL poisoned-transaction defense
 * as {@code AssessmentComponentInsertGuard} (see its javadoc for the full
 * explanation). Added Sprint 5D hardening follow-up: both {@code
 * MeetingPatternService#create} and {@code MeetingPatternService#update}
 * previously called {@code meetingPatternRepository.saveAndFlush} directly
 * against their own {@code @Transactional} connection, so a genuine
 * overlap would poison that connection's transaction even though the Java
 * exception was caught, turning the intended {@code RoomOverlap} outcome
 * (named in both {@code CreateResult} and {@code UpdateResult}) into an
 * unhandled 500 at commit. One shared {@code saveAndFlush} method serves
 * both call sites — JPA's {@code saveAndFlush} already handles insert vs.
 * update transparently based on the entity's own persistence state.
 */
@Component
public class MeetingPatternWriteGuard {

    private final MeetingPatternRepository meetingPatternRepository;

    public MeetingPatternWriteGuard(MeetingPatternRepository meetingPatternRepository) {
        this.meetingPatternRepository = meetingPatternRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveAndFlush(MeetingPattern pattern) {
        meetingPatternRepository.saveAndFlush(pattern);
    }
}
