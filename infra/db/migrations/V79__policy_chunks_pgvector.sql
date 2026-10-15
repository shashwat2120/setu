-- Retrieval-augmented generation over college policy documents (canonical spec §10: tenant and document-ACL checks occur BEFORE
-- retrieval). A policy document is split into chunks; each chunk carries a pgvector embedding plus a full-text vector, and the search
-- ranks by both (hybrid retrieval).
--
-- Owned by institution beside policy_documents (the AI module never gets a datasource). tenant_id is repeated on the chunk so the tenant
-- filter is a plain column predicate in the same query as the vector search, never a post-hoc filter. The document-ACL column stays on
-- policy_documents (one place) and is applied by joining to it.
--
-- embedding_model records which embedder produced a vector: vectors from different models are not comparable, so a query only ever
-- searches chunks embedded by the model currently configured, and chunks from an older model are re-embedded in the background.
-- embedding is nullable so a document is searchable by text immediately even if the embedder is slow or down.
-- 768 dimensions matches the built-in local embedder and nomic-embed-text.
CREATE TABLE institution.policy_chunks (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        UUID NOT NULL REFERENCES institution.colleges (id),
    document_id      UUID NOT NULL REFERENCES institution.policy_documents (id) ON DELETE CASCADE,
    chunk_index      INT NOT NULL CHECK (chunk_index >= 0),
    content          TEXT NOT NULL,
    embedding        VECTOR(768) NULL,
    embedding_model  TEXT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    search_vector    TSVECTOR GENERATED ALWAYS AS (to_tsvector('english', content)) STORED,
    CONSTRAINT uq_policy_chunks_document_index UNIQUE (document_id, chunk_index),
    CONSTRAINT ck_policy_chunks_embedding_has_model CHECK ((embedding IS NULL) = (embedding_model IS NULL))
);
CREATE INDEX idx_policy_chunks_tenant ON institution.policy_chunks (tenant_id);
CREATE INDEX idx_policy_chunks_search ON institution.policy_chunks USING GIN (search_vector);
CREATE INDEX idx_policy_chunks_embedding ON institution.policy_chunks USING hnsw (embedding vector_cosine_ops);
