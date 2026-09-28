-- Spring Modulith's OWN infrastructure table for its event-publication
-- registry (spring-modulith-starter-jpa) — how it tracks completion/retry of
-- application events before external (Kafka) publication, i.e. the actual
-- mechanism behind the "commit-before-publish" outbox guarantee already
-- documented throughout docs/TRD.md §4 and docs/kafka-event-catalog.md.
-- NOT one of the 83 ERD domain tables — discovered during Day 2 runtime
-- verification (Hibernate schema validation failed with "missing table
-- [event_publication]" once a live Postgres was available to test against).
--
-- Field list and @Table(name="EVENT_PUBLICATION") confirmed by decompiling
-- org.springframework.modulith.events.jpa.JpaEventPublication /
-- DefaultJpaEventPublication in spring-modulith-events-jpa:2.1.1 — not
-- guessed from memory of older documentation, which may not reflect this
-- version's newer resubmission-tracking columns.
--
-- Lives in the connection's default schema (`public`), matching V1's
-- schema_info table — Modulith's @Table annotation carries no explicit
-- schema, so Hibernate resolves it via the default search_path.
CREATE TABLE event_publication (
    id                      UUID NOT NULL PRIMARY KEY,
    listener_id             TEXT NOT NULL,
    event_type              TEXT NOT NULL,
    serialized_event        TEXT NOT NULL,
    publication_date        TIMESTAMPTZ NOT NULL,
    completion_date         TIMESTAMPTZ NULL,
    last_resubmission_date  TIMESTAMPTZ NULL,
    completion_attempts     INT NOT NULL DEFAULT 0,
    status                  TEXT NOT NULL
);
CREATE INDEX idx_event_publication_incomplete ON event_publication (listener_id) WHERE completion_date IS NULL;
