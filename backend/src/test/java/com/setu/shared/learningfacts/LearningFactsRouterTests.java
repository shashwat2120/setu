package com.setu.shared.learningfacts;

import com.setu.shared.internalcall.LocalReads;
import com.setu.shared.learningfacts.LearningFacts.StudentCounts;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Where a learning question goes (see AttendanceCountsRouterTests): the service, this process's own data, or this process's own data inside a LocalReads scope. */
class LearningFactsRouterTests {

    private static final UUID STUDENT = UUID.randomUUID();
    private static final UUID SECTION = UUID.randomUUID();
    private static final StudentCounts LOCAL = new StudentCounts(1, 0, 1, 0);
    private static final StudentCounts SERVICE = new StudentCounts(9, 3, 9, 4);

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T bean) {
        ObjectProvider<T> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(bean);
        return p;
    }

    private static LearningFacts answering(StudentCounts counts) {
        return new LearningFacts() {
            @Override
            public Map<UUID, StudentCounts> studentCounts(Collection<UUID> studentIds) {
                return Map.of(STUDENT, counts);
            }

            @Override
            public Map<UUID, List<PublishedAssignment>> publishedAssignments(Collection<UUID> sectionIds) {
                return Map.of(SECTION, List.of());
            }

            @Override
            public Map<UUID, List<LessonRef>> lessonsOfSections(Collection<UUID> sectionIds) {
                return Map.of(SECTION, List.of());
            }

            @Override
            public Map<UUID, Set<UUID>> completedLessons(Collection<UUID> studentIds) {
                return Map.of(STUDENT, Set.of());
            }
        };
    }

    private static RemoteLearningFacts remoteAnswering(StudentCounts counts) {
        var remote = mock(RemoteLearningFacts.class);
        when(remote.studentCounts(List.of(STUDENT))).thenReturn(Map.of(STUDENT, counts));
        return remote;
    }

    @Test
    void theServiceIsAskedUnlessThereIsNoneOrASeedingScopeIsOpen() {
        var router = new LearningFactsRouter(provider(answering(LOCAL)), provider(remoteAnswering(SERVICE)));
        assertThat(router.studentCounts(List.of(STUDENT)).get(STUDENT)).isEqualTo(SERVICE);
        try (LocalReads.Scope ignored = LocalReads.enter()) {
            assertThat(router.studentCounts(List.of(STUDENT)).get(STUDENT)).isEqualTo(LOCAL);
            assertThat(router.publishedAssignments(List.of(SECTION))).containsKey(SECTION);
            assertThat(router.lessonsOfSections(List.of(SECTION))).containsKey(SECTION);
            assertThat(router.completedLessons(List.of(STUDENT))).containsKey(STUDENT);
        }
        assertThat(new LearningFactsRouter(provider(answering(LOCAL)), provider(null)).studentCounts(List.of(STUDENT)).get(STUDENT)).isEqualTo(LOCAL);
    }

    @Test
    void withNeitherItSaysSoForEveryQuestion() {
        var router = new LearningFactsRouter(provider(null), provider(null));
        assertThatThrownBy(() -> router.studentCounts(List.of(STUDENT))).isInstanceOf(LearningFacts.Unavailable.class);
        assertThatThrownBy(() -> router.publishedAssignments(List.of(SECTION))).isInstanceOf(LearningFacts.Unavailable.class);
        assertThatThrownBy(() -> router.lessonsOfSections(List.of(SECTION))).isInstanceOf(LearningFacts.Unavailable.class);
        assertThatThrownBy(() -> router.completedLessons(List.of(STUDENT))).isInstanceOf(LearningFacts.Unavailable.class);
    }
}
