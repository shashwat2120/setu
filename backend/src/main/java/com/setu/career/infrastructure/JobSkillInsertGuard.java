package com.setu.career.infrastructure;

import com.setu.career.domain.JobSkill;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Isolates the racy {@code (job_id, skill_id)}-uniqueness INSERT in its own {@code REQUIRES_NEW} transaction — same PostgreSQL poisoned-transaction defense as {@code AssessmentComponentInsertGuard}. */
@Component
public class JobSkillInsertGuard {

    private final JobSkillRepository jobSkillRepository;

    public JobSkillInsertGuard(JobSkillRepository jobSkillRepository) {
        this.jobSkillRepository = jobSkillRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void insert(JobSkill jobSkill) {
        jobSkillRepository.saveAndFlush(jobSkill);
    }
}
