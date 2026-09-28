package com.setu.institution.infrastructure;

import com.setu.institution.domain.AcademicOrganization;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AcademicOrganizationRepository extends JpaRepository<AcademicOrganization, UUID> {

    List<AcademicOrganization> findByTenantId(UUID tenantId);
}
