package com.setu.career.infrastructure;

import com.setu.career.domain.SkillCategory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Isolates the racy {@code (tenant_id, code)}-uniqueness INSERT in its own {@code REQUIRES_NEW} transaction — same PostgreSQL poisoned-transaction defense as {@code AssessmentComponentInsertGuard}. */
@Component
public class SkillCategoryInsertGuard {

    private final SkillCategoryRepository skillCategoryRepository;

    public SkillCategoryInsertGuard(SkillCategoryRepository skillCategoryRepository) {
        this.skillCategoryRepository = skillCategoryRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void insert(SkillCategory category) {
        skillCategoryRepository.saveAndFlush(category);
    }
}
