package com.setu.catalog.infrastructure;

import com.setu.catalog.domain.CurriculumRequirementCourse;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CurriculumRequirementCourseRepository extends JpaRepository<CurriculumRequirementCourse, UUID> {

    List<CurriculumRequirementCourse> findByRequirementId(UUID requirementId);

    Optional<CurriculumRequirementCourse> findByRequirementIdAndCourseId(UUID requirementId, UUID courseId);
}
