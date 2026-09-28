package com.setu.enrollment.domain;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Day 6 test matrix category E: "end date cannot precede start date" — a pure domain invariant, tested at the entity level since the current service API only ever constructs a fresh assignment with ends_at=null (see enrollment.application.StudentProgramTests for the service-level coverage). */
class StudentProgramTests {

    @Test
    void endsAtBeforeStartsAtIsRejected() {
        assertThatThrownBy(() -> new StudentProgram(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), UUID.randomUUID(), 2026, true, LocalDate.of(2026, 6, 1), LocalDate.of(2026, 1, 1)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void endsAtAfterStartsAtIsAccepted() {
        var studentProgram = new StudentProgram(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), UUID.randomUUID(), 2026, true, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 1));

        assertThat(studentProgram.status()).isEqualTo(StudentProgramStatus.ACTIVE);
    }

    @Test
    void terminalStatusHasNoOutgoingTransition() {
        var studentProgram = new StudentProgram(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), UUID.randomUUID(), 2026, true, LocalDate.of(2026, 1, 1), null);

        studentProgram.transitionTo(StudentProgramStatus.DISCONTINUED, LocalDate.now());

        assertThat(studentProgram.canTransitionTo(StudentProgramStatus.ACTIVE)).isFalse();
    }
}
