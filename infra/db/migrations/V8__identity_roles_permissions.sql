-- Reference/catalog tables — no tenant_id (platform-wide), no audit columns
-- (effectively immutable, seeded not user-edited in v1).
CREATE TABLE identity.roles (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code        TEXT NOT NULL CHECK (code IN ('SUPER_ADMIN','COLLEGE_ADMIN','FACULTY','STUDENT','PLACEMENT_OFFICER','GUARDIAN')),
    name        TEXT NOT NULL,
    scope_type  TEXT NOT NULL CHECK (scope_type IN ('PLATFORM','TENANT')),
    CONSTRAINT uq_roles_code UNIQUE (code)
);

CREATE TABLE identity.permissions (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code        TEXT NOT NULL,
    resource    TEXT NOT NULL,
    action      TEXT NOT NULL,
    CONSTRAINT uq_permissions_code UNIQUE (code),
    CONSTRAINT uq_permissions_resource_action UNIQUE (resource, action)
);
