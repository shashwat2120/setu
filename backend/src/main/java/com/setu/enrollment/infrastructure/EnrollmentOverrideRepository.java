package com.setu.enrollment.infrastructure;

import com.setu.enrollment.domain.EnrollmentOverride;
import com.setu.enrollment.domain.OverrideStatus;
import com.setu.enrollment.domain.OverrideType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EnrollmentOverrideRepository extends JpaRepository<EnrollmentOverride, UUID> {

    List<EnrollmentOverride> findByStudentId(UUID studentId);

    Optional<EnrollmentOverride> findFirstByStudentIdAndSectionIdAndTypeAndStatusOrderByExpiresAtDesc(
            UUID studentId, UUID sectionId, OverrideType type, OverrideStatus status);
}
