-- Setu — initial migration
-- Enables pgvector (used later by Spring AI for embeddings) and records that the
-- migration pipeline itself is wired up correctly before any real schema lands.

CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE schema_info (
    id          SMALLINT PRIMARY KEY DEFAULT 1,
    initialized_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT schema_info_singleton CHECK (id = 1)
);

INSERT INTO schema_info (id) VALUES (1);
