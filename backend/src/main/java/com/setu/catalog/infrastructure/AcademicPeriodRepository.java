package com.setu.catalog.infrastructure;

import com.setu.catalog.domain.AcademicPeriod;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AcademicPeriodRepository extends JpaRepository<AcademicPeriod, UUID> {

    List<AcademicPeriod> findByAcademicYearId(UUID academicYearId);

    List<AcademicPeriod> findByTenantId(UUID tenantId);
}
