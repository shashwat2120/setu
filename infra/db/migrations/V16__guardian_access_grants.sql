CREATE TABLE guardian_access.guardian_access_grants (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID NOT NULL REFERENCES institution.colleges (id),
    student_id  UUID NOT NULL REFERENCES directory.student_profiles (id),
    guardian_id UUID NOT NULL REFERENCES directory.guardians (id),
    granted_by  UUID NOT NULL REFERENCES identity.users (id),
    status      TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','EXPIRED','REVOKED')),
    granted_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at  TIMESTAMPTZ NULL,
    revoked_at  TIMESTAMPTZ NULL,
    version     INT NOT NULL DEFAULT 0,
    CONSTRAINT chk_guardian_access_grants_expiry CHECK (expires_at IS NULL OR expires_at > granted_at)
);
-- Hit on every guardian request to resolve visible students.
CREATE INDEX idx_guardian_access_grants_guardian_active ON guardian_access.guardian_access_grants (guardian_id) WHERE status = 'ACTIVE';
