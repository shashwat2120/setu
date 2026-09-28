package com.setu.institution.infrastructure;

import com.setu.institution.domain.Department;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface DepartmentRepository extends JpaRepository<Department, UUID> {

    List<Department> findByTenantId(UUID tenantId);

    List<Department> findByOrganizationId(UUID organizationId);
}
