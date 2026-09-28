-- Day 14 §AI: the smallest defensible "college policy document" store for
-- ai-explain's searchCollegePolicy tool — no ai_explain schema of its own
-- (AGENTS.md: "the ai-explain module never gets a datasource"), so policy
-- documents are owned by institution (an existing module whose whole
-- purpose is tenant/college-level facts) and read by ai-explain through a
-- narrow application-service port, same as every other cross-module read
-- in this codebase.
--
-- required_permission_code is the document-ACL column: NULL means any
-- authenticated member of the tenant may see it; a non-null value (e.g.
-- 'VIEW_TENANT_DATA') restricts it to callers who hold that permission.
-- PolicyDocumentService filters by this BEFORE a document's content is
-- ever handed to the AI layer (canonical spec §10's "before retrieval,
-- not post-hoc" rule).
--
-- No pgvector/embedding column yet — full-text search (tsvector) is used
-- instead. A disclosed limitation, not an oversight: this project has no
-- document corpus or embedding-generation pipeline yet, and building one
-- is explicitly out of Day 14's "smallest defensible approach" scope.
CREATE TABLE institution.policy_documents (
    id                       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id                UUID NOT NULL REFERENCES institution.colleges (id),
    title                    TEXT NOT NULL,
    content                  TEXT NOT NULL,
    required_permission_code TEXT NULL,
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by               UUID NOT NULL REFERENCES identity.users (id),
    search_vector            TSVECTOR GENERATED ALWAYS AS (
                                 setweight(to_tsvector('english', title), 'A') ||
                                 setweight(to_tsvector('english', content), 'B')
                             ) STORED
);
CREATE INDEX idx_policy_documents_tenant ON institution.policy_documents (tenant_id);
CREATE INDEX idx_policy_documents_search ON institution.policy_documents USING GIN (search_vector);
