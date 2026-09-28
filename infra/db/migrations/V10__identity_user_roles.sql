CREATE TABLE identity.user_roles (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID NOT NULL REFERENCES identity.users (id),
    role_id     UUID NOT NULL REFERENCES identity.roles (id),
    tenant_id   UUID NULL REFERENCES institution.colleges (id),
    starts_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    ends_at     TIMESTAMPTZ NULL,
    CONSTRAINT chk_user_roles_ends_after_starts CHECK (ends_at IS NULL OR ends_at > starts_at)
);
-- Composite unique for the tenant-scoped case, PLUS a partial unique for the
-- NULL-tenant (platform) case — Postgres treats NULL as distinct in a plain
-- unique index, which would otherwise allow duplicate platform-role grants
-- (docs/contradiction-audit.md #4).
CREATE UNIQUE INDEX uq_user_roles_tenant_scoped ON identity.user_roles (user_id, role_id, tenant_id) WHERE tenant_id IS NOT NULL;
CREATE UNIQUE INDEX uq_user_roles_platform_scoped ON identity.user_roles (user_id, role_id) WHERE tenant_id IS NULL;
-- Hottest lookup on this table: "all my roles", hit on every request.
CREATE INDEX idx_user_roles_user ON identity.user_roles (user_id);
