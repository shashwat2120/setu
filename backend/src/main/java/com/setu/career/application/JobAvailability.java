package com.setu.career.application;

import com.setu.career.domain.ApplicationStatus;
import com.setu.career.domain.Job;
import com.setu.career.domain.JobStatus;
import com.setu.career.infrastructure.ApplicationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Whether a job is still taking applications, and why not when it is not: the officer closed it, its deadline has passed, or every opening is filled. Worked out from the
 * job and its selected applications at the moment of asking, so a job that has reached its deadline is closed at that instant with nothing to run or to go wrong, and the
 * answer the student sees is the answer applying will give.
 */
@Service
public class JobAvailability {

    public static final String CLOSED = "CLOSED";
    public static final String DEADLINE_PASSED = "DEADLINE_PASSED";
    public static final String FULL = "FULL";

    /** @param selected how many students have been selected; @param closedReason null while the job takes applications, otherwise CLOSED, DEADLINE_PASSED or FULL */
    public record State(int selected, String closedReason) {
        public boolean open() {
            return closedReason == null;
        }
    }

    private final ApplicationRepository applicationRepository;

    JobAvailability(ApplicationRepository applicationRepository) {
        this.applicationRepository = applicationRepository;
    }

    @Transactional(readOnly = true)
    public Map<UUID, State> forJobs(Collection<Job> jobs, Instant now) {
        Map<UUID, Integer> selected = new HashMap<>();
        if (!jobs.isEmpty()) {
            for (Object[] row : applicationRepository.countByJobForStatus(jobs.stream().map(Job::id).toList(), ApplicationStatus.SELECTED)) {
                selected.put((UUID) row[0], ((Number) row[1]).intValue());
            }
        }
        Map<UUID, State> out = new HashMap<>();
        for (Job job : jobs) {
            out.put(job.id(), stateOf(job, selected.getOrDefault(job.id(), 0), now));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public State forJob(Job job, Instant now) {
        return stateOf(job, (int) applicationRepository.countByJobIdAndStatus(job.id(), ApplicationStatus.SELECTED), now);
    }

    static State stateOf(Job job, int selected, Instant now) {
        var terms = job.terms();
        String reason = null;
        if (job.status() != JobStatus.OPEN) {
            reason = CLOSED;
        } else if (terms.applicationDeadline() != null && !now.isBefore(terms.applicationDeadline())) {
            reason = DEADLINE_PASSED;
        } else if (terms.openings() != null && selected >= terms.openings()) {
            reason = FULL;
        }
        return new State(selected, reason);
    }
}
