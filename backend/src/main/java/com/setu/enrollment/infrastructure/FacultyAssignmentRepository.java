package com.setu.enrollment.infrastructure;

import com.setu.enrollment.domain.FacultyAssignment;
import com.setu.enrollment.domain.FacultyAssignmentRole;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FacultyAssignmentRepository extends JpaRepository<FacultyAssignment, UUID> {

    List<FacultyAssignment> findBySectionId(UUID sectionId);

    List<FacultyAssignment> findByFacultyId(UUID facultyId);

    Optional<FacultyAssignment> findBySectionIdAndRoleAndEndsAtIsNull(UUID sectionId, FacultyAssignmentRole role);

    boolean existsBySectionIdAndFacultyIdAndEndsAtIsNull(UUID sectionId, UUID facultyId);
}
