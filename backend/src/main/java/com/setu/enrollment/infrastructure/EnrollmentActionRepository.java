package com.setu.enrollment.infrastructure;

import com.setu.enrollment.domain.EnrollmentAction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface EnrollmentActionRepository extends JpaRepository<EnrollmentAction, UUID> {

    List<EnrollmentAction> findByEnrollmentIdOrderByCreatedAtAsc(UUID enrollmentId);
}
