package com.setu.shared.attendancefacts;

import com.setu.shared.attendancefacts.AttendanceCounts.Counts;
import com.setu.shared.internalcall.LocalReads;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Where an attendance question goes: the Attendance service when this process is set up to ask it, this process's own attendance data when it is not, and this
 * process's own data inside a {@link LocalReads} scope (the first-start seeding, when the service cannot exist yet). No Spring context.
 */
class AttendanceCountsRouterTests {

    private static final UUID E = UUID.randomUUID();
    private static final Counts FROM_SERVICE = new Counts(7, 9);
    private static final Counts FROM_LOCAL = new Counts(1, 2);

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T bean) {
        ObjectProvider<T> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(bean);
        return p;
    }

    private static AttendanceCounts answering(Counts counts) {
        return ids -> Map.of(E, counts);
    }

    private static RemoteAttendanceCounts remoteAnswering(Counts counts) {
        var remote = mock(RemoteAttendanceCounts.class);
        when(remote.forEnrollments(List.of(E))).thenReturn(Map.of(E, counts));
        return remote;
    }

    private AttendanceCountsRouter router(boolean withService, boolean withLocal) {
        return new AttendanceCountsRouter(provider(withLocal ? answering(FROM_LOCAL) : null), provider(withService ? remoteAnswering(FROM_SERVICE) : null));
    }

    @Test
    void aProcessSetUpToAskTheServiceAsksIt() {
        assertThat(router(true, true).forEnrollments(List.of(E))).containsEntry(E, FROM_SERVICE);
    }

    @Test
    void aProcessWithNoServiceToAskReadsItsOwnData() {
        assertThat(router(false, true).forEnrollments(List.of(E))).containsEntry(E, FROM_LOCAL);
    }

    @Test
    void insideALocalReadsScopeEvenAProcessSetUpToAskTheServiceReadsItsOwnData() {
        var router = router(true, true);
        try (LocalReads.Scope ignored = LocalReads.enter()) {
            assertThat(router.forEnrollments(List.of(E))).containsEntry(E, FROM_LOCAL);
        }
        assertThat(router.forEnrollments(List.of(E))).as("and the service again as soon as the scope is left").containsEntry(E, FROM_SERVICE);
    }

    @Test
    void scopesNestAndLeavingTheInnerOneDoesNotLeaveTheOuter() {
        var router = router(true, true);
        try (LocalReads.Scope outer = LocalReads.enter()) {
            try (LocalReads.Scope inner = LocalReads.enter()) {
                assertThat(LocalReads.active()).isTrue();
            }
            assertThat(LocalReads.active()).as("still inside the outer scope").isTrue();
            assertThat(router.forEnrollments(List.of(E))).containsEntry(E, FROM_LOCAL);
        }
        assertThat(LocalReads.active()).isFalse();
    }

    @Test
    void theScopeBelongsToTheThreadThatOpenedItAndWorkOnAnotherThreadGoesToTheService() throws Exception {
        var router = router(true, true);
        AtomicBoolean otherThreadSawScope = new AtomicBoolean(true);
        AtomicReference<Map<UUID, Counts>> answer = new AtomicReference<>();
        try (LocalReads.Scope ignored = LocalReads.enter()) {
            Thread other = new Thread(() -> {
                otherThreadSawScope.set(LocalReads.active());
                answer.set(router.forEnrollments(List.of(E)));
            });
            other.start();
            other.join();
        }
        assertThat(otherThreadSawScope).isFalse();
        assertThat(answer.get()).containsEntry(E, FROM_SERVICE);
    }

    @Test
    void aScopeIsLeftEvenWhenTheWorkInsideItFails() {
        assertThatThrownBy(() -> {
            try (LocalReads.Scope ignored = LocalReads.enter()) {
                throw new IllegalStateException("seeding failed");
            }
        }).isInstanceOf(IllegalStateException.class);
        assertThat(LocalReads.active()).isFalse();
    }

    @Test
    void aProcessWithNeitherSaysSoInsteadOfAnswering() {
        assertThatThrownBy(() -> router(false, false).forEnrollments(List.of(E))).isInstanceOf(AttendanceCounts.Unavailable.class);
    }
}
