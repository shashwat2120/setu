package com.setu.institution.application;

import com.setu.institution.domain.PolicyDocument;
import com.setu.institution.infrastructure.PolicyDocumentRepository;
import com.setu.shared.authorization.AuthorizationContext;
import com.setu.shared.authorization.ResourceRef;
import com.setu.shared.governance.AuditLogWriter;
import com.setu.shared.tenant.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Day 14 §AI: college policy documents — see V70's header comment for why
 * this module owns them instead of a new {@code ai_explain} schema.
 * {@code search} is the ONLY entry point {@code ai-explain}'s {@code
 * searchCollegePolicy} tool calls, and is deliberately the tenant-ACL
 * boundary itself: tenant is resolved from the CURRENT caller via {@link
 * TenantContext} (never a parameter, so no prompt can redirect it — see
 * that interface's own javadoc), and every candidate row's {@code
 * requiredPermissionCode} is checked BEFORE it is returned — no document
 * the caller isn't allowed to see is ever handed back, let alone to the
 * model (canonical spec §10's before-retrieval rule, never a post-hoc filter).
 */
@org.springframework.modulith.NamedInterface
@Service
public class PolicyDocumentService {

    private static final String MANAGE_PERMISSION = "MANAGE_INSTITUTION";
    private static final int MAX_RESULTS = 5;

    private final PolicyDocumentRepository policyDocumentRepository;
    private final CollegeService collegeService;
    private final TenantContext tenantContext;
    private final AuthorizationContext authorizationContext;
    private final AuditLogWriter auditLogWriter;

    public PolicyDocumentService(PolicyDocumentRepository policyDocumentRepository, CollegeService collegeService,
                                  TenantContext tenantContext, AuthorizationContext authorizationContext,
                                  AuditLogWriter auditLogWriter) {
        this.policyDocumentRepository = policyDocumentRepository;
        this.collegeService = collegeService;
        this.tenantContext = tenantContext;
        this.authorizationContext = authorizationContext;
        this.auditLogWriter = auditLogWriter;
    }

    public sealed interface CreateResult {
        record Success(UUID documentId) implements CreateResult {
        }

        record TenantNotFound() implements CreateResult {
        }
    }

    @Transactional
    public CreateResult create(UUID tenantId, String title, String content, String requiredPermissionCode) {
        if (!collegeService.existsById(tenantId)) {
            return new CreateResult.TenantNotFound();
        }
        authorizationContext.assertCan(MANAGE_PERMISSION, ResourceRef.of("policy_document", UUID.randomUUID(), tenantId));
        UUID actor = authorizationContext.currentUserId();
        PolicyDocument document = new PolicyDocument(UUID.randomUUID(), tenantId, title, content,
            requiredPermissionCode, actor, Instant.now());
        policyDocumentRepository.save(document);
        auditLogWriter.write(tenantId, actor, "POLICY_DOCUMENT_CREATED", "institution.policy_documents",
            document.id(), Map.of("title", title));
        return new CreateResult.Success(document.id());
    }

    public record PolicySearchResult(UUID documentId, String title, String content) {
    }

    /**
     * No tenant/query parameter is ever taken from an AI prompt — the tool
     * layer must resolve them itself ({@code tenantId} from {@link
     * TenantContext}, {@code query} only as the user's own literal
     * question text, never re-derived from retrieved content). Returns an
     * empty list rather than throwing when the caller has no single
     * resolvable tenant (SUPER_ADMIN, or truly no context) — "no policies
     * found" is a safe, honest answer; a thrown exception here would leak
     * more about the caller's own account shape than a policy search ever
     * should.
     */
    @Transactional(readOnly = true)
    public List<PolicySearchResult> search(String query) {
        Optional<UUID> tenantId = tenantContext.tenantId();
        if (tenantId.isEmpty() || query == null || query.isBlank()) {
            return List.of();
        }
        return policyDocumentRepository.search(tenantId.get(), query, MAX_RESULTS).stream()
            .filter(this::callerMaySee)
            .map(d -> new PolicySearchResult(d.id(), d.title(), d.content()))
            .toList();
    }

    private boolean callerMaySee(PolicyDocument document) {
        if (document.requiredPermissionCode() == null) {
            return true;
        }
        return authorizationContext.can(document.requiredPermissionCode(),
            ResourceRef.of("policy_document", document.id(), document.tenantId())).isAllowed();
    }
}
