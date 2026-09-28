package com.setu.catalog.infrastructure;

import com.setu.catalog.domain.Program;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProgramRepository extends JpaRepository<Program, UUID> {

    Optional<Program> findByTenantIdAndCode(UUID tenantId, String code);

    List<Program> findByTenantId(UUID tenantId);
}
