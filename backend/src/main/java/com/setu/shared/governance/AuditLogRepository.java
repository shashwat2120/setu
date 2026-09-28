package com.setu.shared.governance;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

interface AuditLogRepository extends JpaRepository<AuditLogEntry, UUID> {

    Page<AuditLogEntry> findAllByOrderByCreatedAtDesc(Pageable pageable);

    Page<AuditLogEntry> findByTenantIdOrderByCreatedAtDesc(UUID tenantId, Pageable pageable);
}
