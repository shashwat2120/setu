package com.setu.catalog.infrastructure;

import com.setu.catalog.domain.AcademicYear;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface AcademicYearRepository extends JpaRepository<AcademicYear, UUID> {

    Optional<AcademicYear> findByTenantIdAndStatus(UUID tenantId, AcademicYear.YearStatus status);
}
