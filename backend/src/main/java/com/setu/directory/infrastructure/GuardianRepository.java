package com.setu.directory.infrastructure;

import com.setu.directory.domain.Guardian;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface GuardianRepository extends JpaRepository<Guardian, UUID> {

    Optional<Guardian> findByPersonIdAndTenantId(UUID personId, UUID tenantId);
}
