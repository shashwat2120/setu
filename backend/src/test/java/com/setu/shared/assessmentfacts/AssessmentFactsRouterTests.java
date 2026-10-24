package com.setu.shared.assessmentfacts;

import com.setu.shared.internalcall.LocalReads;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Where an assessment question goes (see AttendanceCountsRouterTests): the service, this process's own data, or this process's own data inside a LocalReads scope. */
class AssessmentFactsRouterTests {

    private static final UUID E = UUID.randomUUID();
    private static final UUID S = UUID.randomUUID();

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T bean) {
        ObjectProvider<T> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(bean);
        return p;
    }

    /** A reader that answers one tagged grade and one tagged assessment, so a test can tell which reader was asked. */
    private static AssessmentFacts tagged(String tag) {
        return new AssessmentFacts() {
            @Override
            public Map<UUID, List<GradeFact>> currentGrades(Collection<UUID> enrollmentIds) {
                return Map.of(E, List.of(new GradeFact(E, UUID.randomUUID(), null, tag, null, null, null, null)));
            }

            @Override
            public Map<UUID, List<VisibleAssessment>> visibleAssessments(Collection<UUID> sectionIds) {
                return Map.of(S, List.of(new VisibleAssessment(UUID.randomUUID(), tag, null)));
            }
        };
    }

    private static RemoteAssessmentFacts remoteTagged(String tag) {
        var remote = mock(RemoteAssessmentFacts.class);
        var delegate = tagged(tag);
        when(remote.currentGrades(List.of(E))).thenReturn(delegate.currentGrades(List.of(E)));
        when(remote.visibleAssessments(List.of(S))).thenReturn(delegate.visibleAssessments(List.of(S)));
        return remote;
    }

    private static String gradeTag(AssessmentFacts facts) {
        return facts.currentGrades(List.of(E)).get(E).get(0).gradeCode();
    }

    @Test
    void theServiceIsAskedUnlessThereIsNone() {
        assertThat(gradeTag(new AssessmentFactsRouter(provider(tagged("local")), provider(remoteTagged("service"))))).isEqualTo("service");
        assertThat(gradeTag(new AssessmentFactsRouter(provider(tagged("local")), provider(null)))).isEqualTo("local");
    }

    @Test
    void insideALocalReadsScopeItIsAlwaysTheLocalDataForBothQuestions() {
        var router = new AssessmentFactsRouter(provider(tagged("local")), provider(remoteTagged("service")));
        try (LocalReads.Scope ignored = LocalReads.enter()) {
            assertThat(gradeTag(router)).isEqualTo("local");
            assertThat(router.visibleAssessments(List.of(S)).get(S).get(0).title()).isEqualTo("local");
        }
        assertThat(gradeTag(router)).isEqualTo("service");
        assertThat(router.visibleAssessments(List.of(S)).get(S).get(0).title()).isEqualTo("service");
    }

    @Test
    void withNeitherItSaysSo() {
        var router = new AssessmentFactsRouter(provider(null), provider(null));
        assertThatThrownBy(() -> router.currentGrades(List.of(E))).isInstanceOf(AssessmentFacts.Unavailable.class);
        assertThatThrownBy(() -> router.visibleAssessments(List.of(S))).isInstanceOf(AssessmentFacts.Unavailable.class);
    }
}
