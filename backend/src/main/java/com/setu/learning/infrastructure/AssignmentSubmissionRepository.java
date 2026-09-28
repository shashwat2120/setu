package com.setu.learning.infrastructure;

import com.setu.learning.domain.AssignmentSubmission;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AssignmentSubmissionRepository extends JpaRepository<AssignmentSubmission, UUID> {

    List<AssignmentSubmission> findByAssignmentId(UUID assignmentId);

    List<AssignmentSubmission> findByAssignmentIdAndStudentId(UUID assignmentId, UUID studentId);

    int countByAssignmentIdAndStudentId(UUID assignmentId, UUID studentId);

    List<AssignmentSubmission> findByStudentId(UUID studentId);
}
