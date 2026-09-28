-- Append-only audit trail — no update, no delete, ever.
CREATE TABLE enrollment.enrollment_actions (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    enrollment_id   UUID NOT NULL REFERENCES enrollment.enrollments (id),
    action          TEXT NOT NULL,
    actor_user_id   UUID NOT NULL REFERENCES identity.users (id),
    reason          TEXT NULL,
    metadata_json   JSONB NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_enrollment_actions_enrollment_created ON enrollment.enrollment_actions (enrollment_id, created_at);

-- `status` added here to match the already-designed Enrollment Override
-- state machine (docs/state-machines.md #13: ACTIVE/USED/EXPIRED/REVOKED) —
-- applying an already-established design, not inventing a new one.
CREATE TABLE enrollment.enrollment_overrides (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES institution.colleges (id),
    student_id      UUID NOT NULL REFERENCES directory.student_profiles (id),
    section_id      UUID NOT NULL REFERENCES enrollment.course_sections (id),
    type            TEXT NOT NULL CHECK (type IN ('PREREQUISITE','CAPACITY','CREDIT_LIMIT','HOLD')),
    status          TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','USED','EXPIRED','REVOKED')),
    reason          TEXT NOT NULL,
    approved_by     UUID NOT NULL REFERENCES identity.users (id),
    expires_at      TIMESTAMPTZ NULL
);
CREATE INDEX idx_enrollment_overrides_student ON enrollment.enrollment_overrides (student_id);

CREATE TABLE enrollment.waitlist_entries (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID NOT NULL REFERENCES institution.colleges (id),
    section_id  UUID NOT NULL REFERENCES enrollment.course_sections (id),
    student_id  UUID NOT NULL REFERENCES directory.student_profiles (id),
    position    INT NOT NULL,
    status      TEXT NOT NULL DEFAULT 'WAITING' CHECK (status IN ('WAITING','OFFERED','EXPIRED','CONVERTED','WITHDRAWN')),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_waitlist_entries_section_student UNIQUE (section_id, student_id)
);
CREATE INDEX idx_waitlist_entries_section_position ON enrollment.waitlist_entries (section_id, position);
