package com.setu.finance.infrastructure;

import com.setu.finance.domain.FeeStructure;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface FeeStructureRepository extends JpaRepository<FeeStructure, UUID> {

    List<FeeStructure> findByTenantId(UUID tenantId);
}
