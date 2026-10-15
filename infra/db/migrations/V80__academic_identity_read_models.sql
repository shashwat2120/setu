-- The Academic service's local copy of the Identity data it needs for every request (Sprint 6A.3, docs/contracts/academic-service.md).
--
-- Academic authorizes with the shared engine, which needs to know who holds which role and what each role may do, and it shows people
-- by name. Both are Identity-owned. Calling Identity per request would make Identity a runtime dependency of every read, so Academic
-- keeps these four small tables instead, kept current by Identity's events (UserRoleAssigned, UserRoleRevoked, PersonRegistered,
-- PersonUpdated). They live in the `institution` schema, which is Academic's, and are never written by anything but the projector.
--
-- The initial content is copied from the identity schema below. That works only because there is still one database (6B.2 splits them):
-- once there are two, the copy becomes a snapshot export/import and these INSERT ... SELECT statements go away. The role-to-permission
-- matrix is reference data that changes only by migration, so it is copied once here; a later migration that changes
-- identity.role_permissions must change institution.rm_role_permissions in the same migration, and ReadModelParityTests fails if they drift.

CREATE TABLE institution.rm_role_grants (
    user_role_id UUID PRIMARY KEY,
    user_id      UUID NOT NULL,
    tenant_id    UUID NULL,
    role_code    TEXT NOT NULL,
    starts_at    TIMESTAMPTZ NOT NULL,
    ends_at      TIMESTAMPTZ NULL
);
CREATE INDEX idx_rm_role_grants_user ON institution.rm_role_grants (user_id);

CREATE TABLE institution.rm_role_permissions (
    role_code       TEXT NOT NULL,
    permission_code TEXT NOT NULL,
    PRIMARY KEY (role_code, permission_code)
);

CREATE TABLE institution.rm_persons (
    person_id      UUID PRIMARY KEY,
    user_id        UUID NULL,
    legal_name     TEXT NOT NULL,
    preferred_name TEXT NULL,
    email          TEXT NULL,
    phone          TEXT NULL
);
CREATE UNIQUE INDEX uq_rm_persons_user ON institution.rm_persons (user_id) WHERE user_id IS NOT NULL;

-- Events the projector has already applied, by outbox event id, in the same transaction as the change (Kafka delivers at least once).
CREATE TABLE institution.rm_processed_events (
    event_id     UUID PRIMARY KEY,
    event_type   TEXT NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO institution.rm_role_grants (user_role_id, user_id, tenant_id, role_code, starts_at, ends_at)
SELECT ur.id, ur.user_id, ur.tenant_id, r.code, ur.starts_at, ur.ends_at
FROM identity.user_roles ur
JOIN identity.roles r ON r.id = ur.role_id;

INSERT INTO institution.rm_role_permissions (role_code, permission_code)
SELECT r.code, rp.permission_code
FROM identity.role_permissions rp
JOIN identity.roles r ON r.id = rp.role_id;

INSERT INTO institution.rm_persons (person_id, user_id, legal_name, preferred_name, email, phone)
SELECT p.id, p.user_id, p.legal_name, p.preferred_name, p.email::text, p.phone
FROM identity.persons p;

COMMENT ON TABLE institution.rm_role_grants IS 'Academic read model of identity.user_roles, fed by UserRoleAssigned/UserRoleRevoked. Read by the authorization engine.';
COMMENT ON TABLE institution.rm_role_permissions IS 'Academic copy of the role-to-permission matrix (reference data, changed only by migration). Parity with identity is tested.';
COMMENT ON TABLE institution.rm_persons IS 'Academic read model of identity.persons (name and contact only), fed by PersonRegistered/PersonUpdated.';
COMMENT ON TABLE institution.rm_processed_events IS 'Identity events the Academic projector has already applied, by outbox event id.';
