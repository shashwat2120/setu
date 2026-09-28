package com.setu.directory.infrastructure;

import com.setu.directory.domain.StudentProfile;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface StudentProfileRepository extends JpaRepository<StudentProfile, UUID> {

    Optional<StudentProfile> findByTenantIdAndAdmissionNo(UUID tenantId, String admissionNo);

    Page<StudentProfile> findByTenantId(UUID tenantId, Pageable pageable);

    /** The DB's own uq_student_profiles_tenant_person (V14) enforces at most one row per (tenant, person) regardless of status — this is the friendlier pre-check before that constraint would otherwise fire. */
    boolean existsByTenantIdAndPersonId(UUID tenantId, UUID personId);

    Optional<StudentProfile> findByTenantIdAndPersonId(UUID tenantId, UUID personId);
}
