package com.setu.shared.assessmentfacts;

import com.setu.shared.internalcall.LocalReads;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The {@link AssessmentFacts} everything else is given: the Assessment service when this process is set up to ask it ({@code setu.assessment.url}), the assessment module's
 * own data when it is not or inside a {@link LocalReads} scope (the first-start seeding), and an honest {@link Unavailable} when it has neither.
 */
@Primary
@Component
class AssessmentFactsRouter implements AssessmentFacts {

    private final ObjectProvider<AssessmentFacts> local;
    private final ObjectProvider<RemoteAssessmentFacts> remote;

    AssessmentFactsRouter(@Qualifier("localAssessmentFacts") ObjectProvider<AssessmentFacts> local, ObjectProvider<RemoteAssessmentFacts> remote) {
        this.local = local;
        this.remote = remote;
    }

    @Override
    public Map<UUID, List<GradeFact>> currentGrades(Collection<UUID> enrollmentIds) {
        return chosen().currentGrades(enrollmentIds);
    }

    @Override
    public Map<UUID, List<VisibleAssessment>> visibleAssessments(Collection<UUID> sectionIds) {
        return chosen().visibleAssessments(sectionIds);
    }

    private AssessmentFacts chosen() {
        AssessmentFacts remoteReader = remote.getIfAvailable();
        AssessmentFacts localReader = local.getIfAvailable();
        if (remoteReader != null && !LocalReads.active()) {
            return remoteReader;
        }
        if (localReader != null) {
            return localReader;
        }
        throw new Unavailable("this process has neither the assessment module nor an Assessment service to ask", null);
    }
}
