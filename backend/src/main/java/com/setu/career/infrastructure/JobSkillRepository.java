package com.setu.career.infrastructure;

import com.setu.career.domain.JobSkill;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface JobSkillRepository extends JpaRepository<JobSkill, UUID> {

    List<JobSkill> findByJobId(UUID jobId);
}
