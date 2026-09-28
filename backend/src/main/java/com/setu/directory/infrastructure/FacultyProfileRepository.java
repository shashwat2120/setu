package com.setu.directory.infrastructure;

import com.setu.directory.domain.FacultyProfile;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface FacultyProfileRepository extends JpaRepository<FacultyProfile, UUID> {

    Optional<FacultyProfile> findByTenantIdAndEmployeeNo(UUID tenantId, String employeeNo);

    Page<FacultyProfile> findByTenantId(UUID tenantId, Pageable pageable);

    boolean existsByTenantIdAndPersonId(UUID tenantId, UUID personId);

    Optional<FacultyProfile> findByTenantIdAndPersonId(UUID tenantId, UUID personId);
}
