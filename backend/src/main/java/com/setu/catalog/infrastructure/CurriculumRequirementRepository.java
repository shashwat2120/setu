package com.setu.catalog.infrastructure;

import com.setu.catalog.domain.CurriculumRequirement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CurriculumRequirementRepository extends JpaRepository<CurriculumRequirement, UUID> {

    List<CurriculumRequirement> findByCurriculumId(UUID curriculumId);
}
