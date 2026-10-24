package com.setu.institution.infrastructure;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Storage and similarity search for policy chunks (pgvector). Every search takes the tenant and the set of document-ACL codes the caller
 * holds and applies both inside the SQL, before anything is ranked or returned (canonical spec §10: before retrieval, never after).
 */
@Repository
public class PolicyChunkStore {

    public record ChunkHit(UUID chunkId, UUID documentId, String title, String content, double score) {
    }

    public record PendingChunk(UUID chunkId, String content) {
    }

    private final JdbcClient jdbc;

    public PolicyChunkStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insertChunk(UUID tenantId, UUID documentId, int index, String content, String embeddingLiteral, String embeddingModel) {
        jdbc.sql("""
                INSERT INTO institution.policy_chunks (tenant_id, document_id, chunk_index, content, embedding, embedding_model)
                VALUES (:tenant, :doc, :idx, :content, CAST(:embedding AS vector), :model)
                ON CONFLICT (document_id, chunk_index) DO NOTHING
                """)
            .param("tenant", tenantId).param("doc", documentId).param("idx", index).param("content", content)
            .param("embedding", embeddingLiteral).param("model", embeddingModel).update();
    }

    public void setEmbedding(UUID chunkId, String embeddingLiteral, String embeddingModel) {
        jdbc.sql("UPDATE institution.policy_chunks SET embedding = CAST(:e AS vector), embedding_model = :m WHERE id = :id")
            .param("e", embeddingLiteral).param("m", embeddingModel).param("id", chunkId).update();
    }

    /** Documents that have no chunks yet (created before chunking existed, or whose indexing failed). */
    public List<UUID> documentsWithoutChunks(int limit) {
        return jdbc.sql("""
                SELECT d.id FROM institution.policy_documents d
                WHERE NOT EXISTS (SELECT 1 FROM institution.policy_chunks c WHERE c.document_id = d.id)
                ORDER BY d.created_at LIMIT :limit
                """).param("limit", limit).query(UUID.class).list();
    }

    /** Chunks with no vector, or a vector from a different model than the one now in use. */
    public List<PendingChunk> chunksNeedingEmbedding(String currentModel, int limit) {
        return jdbc.sql("""
                SELECT id, content FROM institution.policy_chunks
                WHERE embedding IS NULL OR embedding_model <> :model
                ORDER BY created_at LIMIT :limit
                """).param("model", currentModel).param("limit", limit)
            .query((rs, i) -> new PendingChunk(rs.getObject("id", UUID.class), rs.getString("content"))).list();
    }

    /** The distinct document-ACL codes used by this tenant's documents. */
    public List<String> distinctAccessCodes(UUID tenantId) {
        return jdbc.sql("SELECT DISTINCT required_permission_code FROM institution.policy_documents WHERE tenant_id = :t AND required_permission_code IS NOT NULL")
            .param("t", tenantId).query(String.class).list();
    }

    public record DocumentText(UUID tenantId, String content) {
    }

    public java.util.Optional<DocumentText> documentText(UUID documentId) {
        return jdbc.sql("SELECT tenant_id, content FROM institution.policy_documents WHERE id = :id").param("id", documentId)
            .query((rs, i) -> new DocumentText(rs.getObject("tenant_id", UUID.class), rs.getString("content"))).optional();
    }

    /** Nearest chunks by cosine similarity among chunks embedded by {@code model}; {@code score} is the similarity (1 = identical). */
    public List<ChunkHit> vectorSearch(UUID tenantId, List<String> allowedCodes, String model, String queryLiteral, int limit) {
        return jdbc.sql("""
                SELECT c.id, c.document_id, d.title, c.content, 1 - (c.embedding <=> CAST(:q AS vector)) AS score
                FROM institution.policy_chunks c JOIN institution.policy_documents d ON d.id = c.document_id
                WHERE c.tenant_id = :tenant AND c.embedding_model = :model AND c.embedding IS NOT NULL
                """ + aclClause(allowedCodes) + """
                ORDER BY c.embedding <=> CAST(:q AS vector) LIMIT :limit
                """)
            .param("q", queryLiteral).param("tenant", tenantId).param("model", model).param("limit", limit)
            .params(aclParams(allowedCodes)).query(this::hit).list();
    }

    /** Full-text matches (stemmed, ranked by {@code ts_rank}); works even for chunks that have no vector yet. Every word of the query must appear. */
    public List<ChunkHit> textSearch(UUID tenantId, List<String> allowedCodes, String query, int limit) {
        return textSearch(tenantId, allowedCodes, query, limit, false);
    }

    /**
     * The same, but a passage that has ANY of the query's words qualifies and the ranking puts those with most of them first. Used only when the
     * strict search and the vector search both found nothing (a natural question has words such as "college" or "say" that no passage contains).
     */
    public List<ChunkHit> looseTextSearch(UUID tenantId, List<String> allowedCodes, String query, int limit) {
        return textSearch(tenantId, allowedCodes, query, limit, true);
    }

    private List<ChunkHit> textSearch(UUID tenantId, List<String> allowedCodes, String query, int limit, boolean any) {
        String tsquery = any ? "replace(plainto_tsquery('english', :q)::text, '&', '|')::tsquery" : "plainto_tsquery('english', :q)";
        return jdbc.sql(("""
                SELECT c.id, c.document_id, d.title, c.content, ts_rank(c.search_vector, TSQUERY) AS score
                FROM institution.policy_chunks c JOIN institution.policy_documents d ON d.id = c.document_id
                WHERE c.tenant_id = :tenant AND c.search_vector @@ TSQUERY
                """ + aclClause(allowedCodes) + """
                ORDER BY score DESC LIMIT :limit
                """).replace("TSQUERY", tsquery))
            .param("q", query).param("tenant", tenantId).param("limit", limit)
            .params(aclParams(allowedCodes)).query(this::hit).list();
    }

    private ChunkHit hit(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new ChunkHit(rs.getObject("id", UUID.class), rs.getObject("document_id", UUID.class), rs.getString("title"),
            rs.getString("content"), rs.getDouble("score"));
    }

    private static String aclClause(List<String> allowedCodes) {
        return allowedCodes.isEmpty()
            ? " AND d.required_permission_code IS NULL "
            : " AND (d.required_permission_code IS NULL OR d.required_permission_code IN (:codes)) ";
    }

    private static java.util.Map<String, Object> aclParams(List<String> allowedCodes) {
        return allowedCodes.isEmpty() ? java.util.Map.of() : java.util.Map.of("codes", allowedCodes);
    }

    /** pgvector text form of a vector: {@code [0.1,0.2,...]}. */
    public static String literal(float[] vector) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(vector[i]);
        }
        return sb.append(']').toString();
    }
}
