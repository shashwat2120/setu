package com.setu.enrollment.infrastructure;

import com.setu.enrollment.domain.Enrollment;
import com.setu.enrollment.domain.EnrollmentStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EnrollmentRepository extends JpaRepository<Enrollment, UUID> {

    Optional<Enrollment> findBySectionIdAndStudentId(UUID sectionId, UUID studentId);

    long countBySectionIdAndStatus(UUID sectionId, EnrollmentStatus status);

    Page<Enrollment> findByStudentId(UUID studentId, Pageable pageable);

    List<Enrollment> findByStudentIdAndStatusIn(UUID studentId, Collection<EnrollmentStatus> statuses);

    Page<Enrollment> findBySectionId(UUID sectionId, Pageable pageable);
}
