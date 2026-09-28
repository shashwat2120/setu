CREATE TABLE risk.recommendations (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    risk_score_id   UUID NOT NULL REFERENCES risk.risk_scores (id),
    action_code     TEXT NOT NULL,
    generated_by    TEXT NOT NULL CHECK (generated_by IN ('RULE','AI')),
    status          TEXT NOT NULL DEFAULT 'PROPOSED' CHECK (status IN ('PROPOSED','ACCEPTED','DISMISSED'))
);
CREATE INDEX idx_recommendations_risk_score ON risk.recommendations (risk_score_id);

CREATE TABLE risk.interventions (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID NOT NULL REFERENCES institution.colleges (id),
    student_id          UUID NOT NULL REFERENCES directory.student_profiles (id),
    risk_score_id       UUID NOT NULL REFERENCES risk.risk_scores (id),
    recommendation_id   UUID NULL REFERENCES risk.recommendations (id),
    owner_user_id       UUID NOT NULL REFERENCES identity.users (id),
    status              TEXT NOT NULL DEFAULT 'PLANNED' CHECK (status IN ('PLANNED','IN_PROGRESS','COMPLETED','CANCELLED')),
    due_date            DATE NULL
);
CREATE INDEX idx_interventions_student ON risk.interventions (student_id);

CREATE TABLE risk.intervention_actions (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    intervention_id UUID NOT NULL REFERENCES risk.interventions (id),
    action          TEXT NOT NULL,
    actor_user_id   UUID NOT NULL REFERENCES identity.users (id),
    notes           TEXT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_intervention_actions_intervention ON risk.intervention_actions (intervention_id);
