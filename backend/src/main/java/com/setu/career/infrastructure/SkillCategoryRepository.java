package com.setu.career.infrastructure;

import com.setu.career.domain.SkillCategory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SkillCategoryRepository extends JpaRepository<SkillCategory, UUID> {

    List<SkillCategory> findByTenantId(UUID tenantId);

    boolean existsByTenantIdAndCodeIgnoreCaseAndIdNot(UUID tenantId, String code, UUID id);
}
