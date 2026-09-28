package com.setu.assessment.infrastructure;

import com.setu.assessment.domain.Assessment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AssessmentRepository extends JpaRepository<Assessment, UUID> {

    List<Assessment> findBySectionId(UUID sectionId);
}
