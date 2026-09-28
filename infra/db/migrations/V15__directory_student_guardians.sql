CREATE TABLE directory.student_guardians (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id               UUID NOT NULL REFERENCES institution.colleges (id),
    student_id              UUID NOT NULL REFERENCES directory.student_profiles (id),
    guardian_id             UUID NOT NULL REFERENCES directory.guardians (id),
    relationship_type       TEXT NOT NULL,
    is_primary              BOOLEAN NOT NULL DEFAULT false,
    is_emergency_contact    BOOLEAN NOT NULL DEFAULT false,
    notification_flags      JSONB NOT NULL DEFAULT '{}',
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by              UUID NOT NULL REFERENCES identity.users (id),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by              UUID NOT NULL REFERENCES identity.users (id),
    version                 INT NOT NULL DEFAULT 0,
    CONSTRAINT uq_student_guardians_relationship UNIQUE (student_id, guardian_id, relationship_type)
);
CREATE INDEX idx_student_guardians_guardian ON directory.student_guardians (guardian_id);
