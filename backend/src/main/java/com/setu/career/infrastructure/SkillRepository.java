package com.setu.career.infrastructure;

import com.setu.career.domain.Skill;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SkillRepository extends JpaRepository<Skill, UUID> {

    List<Skill> findByTenantId(UUID tenantId);

    boolean existsByTenantIdAndCodeIgnoreCaseAndIdNot(UUID tenantId, String code, UUID id);
}
