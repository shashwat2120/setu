package com.setu.career.infrastructure;

import com.setu.career.domain.StudentSkill;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StudentSkillRepository extends JpaRepository<StudentSkill, UUID> {

    List<StudentSkill> findByStudentId(UUID studentId);

    Optional<StudentSkill> findByStudentIdAndSkillId(UUID studentId, UUID skillId);
}
