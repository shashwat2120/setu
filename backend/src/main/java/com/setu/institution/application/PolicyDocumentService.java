package com.setu.institution.application;

import com.setu.institution.domain.PolicyDocument;
import com.setu.institution.infrastructure.PolicyChunkStore;
import com.setu.institution.infrastructure.PolicyDocumentRepository;
import com.setu.shared.ai.TextEmbedder;
import com.setu.shared.authorization.AuthorizationContext;
import com.setu.shared.authorization.ResourceRef;
import com.setu.shared.governance.AuditLogWriter;
import com.setu.shared.tenant.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
    private static final int CANDIDATES = 10;
    private static final int RRF_K = 60;

    private final PolicyDocumentRepository policyDocumentRepository;
    private final CollegeService collegeService;
    private final TenantContext tenantContext;
    private final AuthorizationContext authorizationContext;
    private final AuditLogWriter auditLogWriter;
    private final PolicyChunkStore chunkStore;
    private final PolicyIndexer indexer;
    private final TextEmbedder embedder;

    public PolicyDocumentService(PolicyDocumentRepository policyDocumentRepository, CollegeService collegeService,
                                  TenantContext tenantContext, AuthorizationContext authorizationContext,
                                  AuditLogWriter auditLogWriter, PolicyChunkStore chunkStore, PolicyIndexer indexer,
                                  TextEmbedder embedder) {
        this.chunkStore = chunkStore;
        this.indexer = indexer;
        this.embedder = embedder;
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
        policyDocumentRepository.saveAndFlush(document); // flushed so the chunk rows below (plain SQL) can reference it
        indexer.indexDocument(tenantId, document.id(), content);
        auditLogWriter.write(tenantId, actor, "POLICY_DOCUMENT_CREATED", "institution.policy_documents",
            document.id(), Map.of("title", title));
        return new CreateResult.Success(document.id());
    }

    public record PolicySearchResult(UUID documentId, String title, String content) {
    }

    /**
     * Hybrid retrieval over the caller's own college's policy passages: nearest passages by embedding (pgvector) and best text matches
     * (full-text), merged by reciprocal-rank fusion.
     * <p>
     * No tenant/query parameter is ever taken from an AI prompt: the tenant comes from {@link TenantContext}, the query is only the
     * user's own literal question, never re-derived from retrieved content. Both access checks happen BEFORE retrieval, inside the SQL:
     * the tenant, and the document-ACL codes the caller actually holds (each distinct code the college uses is checked once, up front),
     * so a passage the caller may not see is never ranked, returned, or handed to the model. Returns an empty list rather than throwing
     * when the caller has no single resolvable tenant: "no policies found" is a safe, honest answer.
     * <p>
     * If the embedder is unavailable the vector half is skipped and full-text alone answers; a passage below the embedder's similarity
     * floor is treated as unrelated, so a question with no match returns nothing rather than the least-bad passage.
     */
    @Transactional(readOnly = true)
    public List<PolicySearchResult> search(String query) {
        Optional<UUID> tenantId = tenantContext.tenantId();
        if (tenantId.isEmpty() || query == null || query.isBlank()) {
            return List.of();
        }
        List<String> allowed = codesCallerHolds(tenantId.get());

        List<PolicyChunkStore.ChunkHit> vectorHits = embedder.embedQuery(query)
            .map(v -> chunkStore.vectorSearch(tenantId.get(), allowed, embedder.modelId(), PolicyChunkStore.literal(v), CANDIDATES).stream()
                .filter(h -> h.score() >= embedder.minSimilarity()).toList())
            .orElse(List.of());
        List<PolicyChunkStore.ChunkHit> textHits = chunkStore.textSearch(tenantId.get(), allowed, query, CANDIDATES);

        return fuse(vectorHits, textHits).stream()
            .limit(MAX_RESULTS)
            .map(h -> new PolicySearchResult(h.documentId(), h.title(), h.content()))
            .toList();
    }

    /** The document-ACL codes, among those this college's documents use, that the current caller holds. */
    private List<String> codesCallerHolds(UUID tenantId) {
        List<String> held = new ArrayList<>();
        for (String code : chunkStore.distinctAccessCodes(tenantId)) {
            if (authorizationContext.can(code, ResourceRef.of("policy_document", UUID.randomUUID(), tenantId)).isAllowed()) {
                held.add(code);
            }
        }
        return held;
    }

    /** Reciprocal-rank fusion: a passage ranked well by either list rises, one ranked well by both rises most. */
    private static List<PolicyChunkStore.ChunkHit> fuse(List<PolicyChunkStore.ChunkHit> vector, List<PolicyChunkStore.ChunkHit> text) {
        Map<UUID, Double> score = new LinkedHashMap<>();
        Map<UUID, PolicyChunkStore.ChunkHit> byId = new LinkedHashMap<>();
        for (List<PolicyChunkStore.ChunkHit> list : List.of(vector, text)) {
            for (int rank = 0; rank < list.size(); rank++) {
                PolicyChunkStore.ChunkHit hit = list.get(rank);
                byId.putIfAbsent(hit.chunkId(), hit);
                score.merge(hit.chunkId(), 1.0 / (RRF_K + rank + 1), Double::sum);
            }
        }
        return score.entrySet().stream()
            .sorted(Map.Entry.<UUID, Double>comparingByValue().reversed())
            .map(e -> byId.get(e.getKey()))
            .toList();
    }
}
