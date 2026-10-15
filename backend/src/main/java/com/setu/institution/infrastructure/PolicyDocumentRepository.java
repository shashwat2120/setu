package com.setu.institution.infrastructure;

import com.setu.institution.domain.PolicyDocument;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PolicyDocumentRepository extends JpaRepository<PolicyDocument, UUID> {

    // Search is no longer here: see PolicyChunkStore (hybrid pgvector and full-text retrieval over passages, access checked in SQL).

    List<PolicyDocument> findByTenantId(UUID tenantId);
}
