package com.setu.learning.infrastructure;

import com.setu.learning.domain.CourseModule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CourseModuleRepository extends JpaRepository<CourseModule, UUID> {

    List<CourseModule> findBySectionIdOrderBySequenceAsc(UUID sectionId);
}
