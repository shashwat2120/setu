package com.setu.catalog.infrastructure;

import com.setu.catalog.domain.CoursePrerequisite;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CoursePrerequisiteRepository extends JpaRepository<CoursePrerequisite, UUID> {

    List<CoursePrerequisite> findByCourseId(UUID courseId);

    List<CoursePrerequisite> findByPrerequisiteCourseId(UUID prerequisiteCourseId);

    Optional<CoursePrerequisite> findByCourseIdAndPrerequisiteCourseId(UUID courseId, UUID prerequisiteCourseId);
}
