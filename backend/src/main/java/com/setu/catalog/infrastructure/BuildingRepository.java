package com.setu.catalog.infrastructure;

import com.setu.catalog.domain.Building;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BuildingRepository extends JpaRepository<Building, UUID> {

    Optional<Building> findByTenantIdAndCode(UUID tenantId, String code);

    List<Building> findByTenantId(UUID tenantId);
}
