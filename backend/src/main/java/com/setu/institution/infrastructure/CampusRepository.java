package com.setu.institution.infrastructure;

import com.setu.institution.domain.Campus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CampusRepository extends JpaRepository<Campus, UUID> {

    List<Campus> findByTenantId(UUID tenantId);
}
