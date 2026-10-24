package com.setu.shared.learningfacts;

import com.setu.shared.internalcall.LocalReads;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The {@link LearningFacts} everything else is given: the Learning service when this process is set up to ask it ({@code setu.learning.url}), the learning module's own
 * data when it is not or inside a {@link LocalReads} scope (the first-start seeding), and an honest {@link Unavailable} when it has neither.
 */
@Primary
@Component
class LearningFactsRouter implements LearningFacts {

    private final ObjectProvider<LearningFacts> local;
    private final ObjectProvider<RemoteLearningFacts> remote;

    LearningFactsRouter(@Qualifier("localLearningFacts") ObjectProvider<LearningFacts> local, ObjectProvider<RemoteLearningFacts> remote) {
        this.local = local;
        this.remote = remote;
    }

    @Override
    public Map<UUID, StudentCounts> studentCounts(Collection<UUID> studentIds) {
        return chosen().studentCounts(studentIds);
    }

    @Override
    public Map<UUID, List<PublishedAssignment>> publishedAssignments(Collection<UUID> sectionIds) {
        return chosen().publishedAssignments(sectionIds);
    }

    @Override
    public Map<UUID, List<LessonRef>> lessonsOfSections(Collection<UUID> sectionIds) {
        return chosen().lessonsOfSections(sectionIds);
    }

    @Override
    public Map<UUID, Set<UUID>> completedLessons(Collection<UUID> studentIds) {
        return chosen().completedLessons(studentIds);
    }

    private LearningFacts chosen() {
        LearningFacts remoteReader = remote.getIfAvailable();
        LearningFacts localReader = local.getIfAvailable();
        if (remoteReader != null && !LocalReads.active()) {
            return remoteReader;
        }
        if (localReader != null) {
            return localReader;
        }
        throw new Unavailable("this process has neither the learning module nor a Learning service to ask", null);
    }
}
