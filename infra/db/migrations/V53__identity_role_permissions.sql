-- Sprint 5B Day 4 gap-closure, disclosed per the Day 4 report: V8's own
-- comment already said "the full RBAC permission catalog is Sprint 5B
-- (authorization implementation) work" — this is that work, not a new
-- decision. role_permissions was always implied by "roles, permissions,
-- role_permissions, user_roles" in the Day 4 instruction but never created;
-- V8 only ever created `roles` and `permissions` as standalone catalogs.
CREATE TABLE identity.role_permissions (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    role_id         UUID NOT NULL REFERENCES identity.roles (id),
    permission_code TEXT NOT NULL REFERENCES identity.permissions (code),
    CONSTRAINT uq_role_permissions_role_code UNIQUE (role_id, permission_code)
);
CREATE INDEX idx_role_permissions_role ON identity.role_permissions (role_id);
