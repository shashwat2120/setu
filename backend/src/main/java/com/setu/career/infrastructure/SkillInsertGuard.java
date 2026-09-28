package com.setu.career.infrastructure;

import com.setu.career.domain.Skill;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Isolates the racy {@code (tenant_id, code)}-uniqueness INSERT in its own {@code REQUIRES_NEW} transaction — same PostgreSQL poisoned-transaction defense as {@code AssessmentComponentInsertGuard}. */
@Component
public class SkillInsertGuard {

    private final SkillRepository skillRepository;

    public SkillInsertGuard(SkillRepository skillRepository) {
        this.skillRepository = skillRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void insert(Skill skill) {
        skillRepository.saveAndFlush(skill);
    }
}
