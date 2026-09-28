-- Needed by catalog.meeting_patterns' room/faculty overlap EXCLUDE constraint (V29).
CREATE EXTENSION IF NOT EXISTS btree_gist;

-- Needed for case-insensitive email/username columns (identity.users,
-- identity.persons) — not identified as a required extension in the
-- original migration-order.md plan; added here since it must land before
-- V5 uses the CITEXT type. Flagged as a deviation in the Day 1-2 report.
CREATE EXTENSION IF NOT EXISTS citext;
