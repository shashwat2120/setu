package com.setu.career.infrastructure;

import com.setu.career.domain.StudentSkill;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Isolates the racy {@code (student_id, skill_id)}-uniqueness INSERT in its own {@code REQUIRES_NEW} transaction — same PostgreSQL poisoned-transaction defense as {@code AssessmentComponentInsertGuard}. */
@Component
public class StudentSkillInsertGuard {

    private final StudentSkillRepository studentSkillRepository;

    public StudentSkillInsertGuard(StudentSkillRepository studentSkillRepository) {
        this.studentSkillRepository = studentSkillRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void insert(StudentSkill studentSkill) {
        studentSkillRepository.saveAndFlush(studentSkill);
    }
}
