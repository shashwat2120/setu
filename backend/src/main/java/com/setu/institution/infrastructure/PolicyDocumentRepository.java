package com.setu.institution.infrastructure;

import com.setu.institution.domain.PolicyDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface PolicyDocumentRepository extends JpaRepository<PolicyDocument, UUID> {

    /**
     * Tenant filter is applied in the SQL itself (hard boundary, never a
     * post-hoc application-code filter) — the per-document {@code
     * required_permission_code} ACL check happens afterward in {@link
     * com.setu.institution.application.PolicyDocumentService}, since
     * evaluating a permission isn't expressible as a plain SQL predicate
     * here. Ranked by {@code ts_rank}, capped by the caller's LIMIT via
     * Pageable.
     */
    @Query(value = "SELECT * FROM institution.policy_documents "
        + "WHERE tenant_id = :tenantId AND search_vector @@ plainto_tsquery('english', :query) "
        + "ORDER BY ts_rank(search_vector, plainto_tsquery('english', :query)) DESC "
        + "LIMIT :limit", nativeQuery = true)
    List<PolicyDocument> search(@Param("tenantId") UUID tenantId, @Param("query") String query, @Param("limit") int limit);

    List<PolicyDocument> findByTenantId(UUID tenantId);
}
