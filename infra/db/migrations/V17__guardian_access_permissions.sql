CREATE TABLE guardian_access.guardian_access_permissions (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    grant_id        UUID NOT NULL REFERENCES guardian_access.guardian_access_grants (id),
    permission_code TEXT NOT NULL REFERENCES identity.permissions (code),
    CONSTRAINT uq_guardian_access_permissions_grant_code UNIQUE (grant_id, permission_code)
);
