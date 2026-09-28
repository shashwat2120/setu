-- Append-only. feature_snapshot (full values, not just the hash) added per
-- docs/erd-audit-2.md finding on reproducibility (a hash alone can verify
-- but not reconstruct).
CREATE TABLE risk.risk_scores (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id               UUID NOT NULL REFERENCES institution.colleges (id),
    student_id              UUID NOT NULL REFERENCES directory.student_profiles (id),
    score                   NUMERIC(6,3) NOT NULL,
    band                    TEXT NOT NULL CHECK (band IN ('LOW','MEDIUM','HIGH')),
    rule_version            TEXT NOT NULL,
    feature_snapshot_hash   TEXT NOT NULL,
    -- TEXT, deliberately NOT json/jsonb: feature_snapshot_hash is a checksum over this column's exact stored
    -- text (Day 13 reproducibility contract). A json/jsonb column lets Hibernate's JSON type binding
    -- re-serialize the value through its own format mapper on the way to/from the database (different
    -- whitespace than what was hashed before insert), silently breaking the exact-text hash contract on every
    -- round trip. TEXT stores and returns the literal bytes the application wrote — the column still holds
    -- valid JSON text by construction, just without Postgres/Hibernate JSON-aware (re)normalization.
    feature_snapshot        TEXT NOT NULL,
    calculated_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- Added in the final consistency audit's index review — risk history/trend.
CREATE INDEX idx_risk_scores_student_calculated ON risk.risk_scores (student_id, calculated_at DESC);

CREATE TABLE risk.risk_factors (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    risk_score_id   UUID NOT NULL REFERENCES risk.risk_scores (id),
    factor_code     TEXT NOT NULL,
    contribution    NUMERIC(5,3) NULL,
    evidence_refs   JSONB NOT NULL DEFAULT '[]'
);
CREATE INDEX idx_risk_factors_score ON risk.risk_factors (risk_score_id);
