package com.setu.catalog.infrastructure;

import com.setu.catalog.domain.Room;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Isolates the racy room-code-uniqueness INSERT in its own {@code
 * REQUIRES_NEW} transaction — same PostgreSQL poisoned-transaction defense
 * as {@code AssessmentComponentInsertGuard} (see its javadoc for the full
 * explanation). Added Sprint 5D hardening follow-up: {@code
 * RoomService#create} previously called {@code
 * roomRepository.saveAndFlush} directly against its own {@code
 * @Transactional} connection, so a duplicate code would poison that
 * connection's transaction even though the Java exception was caught,
 * turning the intended {@code CreateResult.CodeAlreadyInUse} into an
 * unhandled 500 at commit.
 */
@Component
public class RoomInsertGuard {

    private final RoomRepository roomRepository;

    public RoomInsertGuard(RoomRepository roomRepository) {
        this.roomRepository = roomRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void insert(Room room) {
        roomRepository.saveAndFlush(room);
    }
}
