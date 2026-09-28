-- One schema per Sprint 5 module, per docs/schema-per-module-map.md (#12 LOCKED).
CREATE SCHEMA identity;
CREATE SCHEMA directory;
CREATE SCHEMA guardian_access;
CREATE SCHEMA institution;
CREATE SCHEMA catalog;
CREATE SCHEMA enrollment;
CREATE SCHEMA attendance;
CREATE SCHEMA learning;
CREATE SCHEMA assessment;
CREATE SCHEMA record;
CREATE SCHEMA finance;
CREATE SCHEMA career;
CREATE SCHEMA risk;
CREATE SCHEMA notification;
CREATE SCHEMA analytics;
-- Cross-cutting: audit_logs, outbox_events, idempotency_keys — not owned by
-- any single module (docs/erd/04-finance-career-success-platform.md).
CREATE SCHEMA governance;
