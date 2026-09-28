-- Day 12 §authorization: docs/authorization-matrix.md's "Finance, career,
-- success" table gives COLLEGE_ADMIN "Tenant, full" on charges/payments/
-- receipts and "Tenant, read" on skills/jobs/applications/placement
-- records, but V55 only ever granted the three existing finance/career
-- codes (MANAGE_FINANCE, MANAGE_CAREER_LISTINGS, VIEW_PLACEMENT_RECORDS) to
-- SUPER_ADMIN and PLACEMENT_OFFICER — COLLEGE_ADMIN had none, and STUDENT
-- had no finance/career permission at all despite the matrix's "Own, read +
-- initiate payment" / "Own, read + apply" cells. This migration closes both
-- gaps: it grants COLLEGE_ADMIN the existing MANAGE_FINANCE and
-- VIEW_PLACEMENT_RECORDS codes, and mints six new codes (granular, per the
-- V54 precedent's own stated rule — "use granular permissions, not a
-- single catch-all") for the student self-service and college-admin
-- read-only cells the matrix requires but no code yet exists for.
--
-- VIEW_FINANCE is deliberately NOT granted to GUARDIAN here — the
-- authorization matrix's own note says it is "not in the default guardian
-- ceiling ... a tenant must explicitly add it" (matching contradiction-audit
-- #6's guardian-grant-ceiling policy). The code exists in the catalog so it
-- CAN be delegated per-student via a guardian_access_grant (the same
-- SUPPORTED_GUARDIAN_PERMISSIONS mechanism Day 10 used for VIEW_GRADES),
-- but no role_permissions row is inserted for GUARDIAN.
INSERT INTO identity.permissions (code, resource, action) VALUES
    ('VIEW_FINANCE', 'finance', 'VIEW_FINANCE'),
    ('INITIATE_PAYMENT', 'finance', 'INITIATE_PAYMENT'),
    ('VIEW_CAREER_LISTINGS', 'career', 'VIEW_CAREER_LISTINGS'),
    ('MANAGE_OWN_SKILLS', 'career', 'MANAGE_OWN_SKILLS'),
    ('APPLY_TO_JOB', 'career', 'APPLY_TO_JOB'),
    ('MANAGE_PLACEMENT_RECORDS', 'career', 'MANAGE_PLACEMENT_RECORDS')
ON CONFLICT (code) DO NOTHING;

-- COLLEGE_ADMIN: the two existing codes the matrix already entitles it to,
-- plus the new read-only career code. Not MANAGE_CAREER_LISTINGS — that one
-- stays PLACEMENT_OFFICER's own ceiling (the matrix gives COLLEGE_ADMIN
-- only "Tenant, read" on skills/jobs/applications, not "full").
INSERT INTO identity.role_permissions (role_id, permission_code)
SELECT r.id, p.code FROM identity.roles r
CROSS JOIN (VALUES ('MANAGE_FINANCE'), ('VIEW_PLACEMENT_RECORDS'), ('VIEW_CAREER_LISTINGS')) AS p(code)
WHERE r.code = 'COLLEGE_ADMIN'
ON CONFLICT (role_id, permission_code) DO NOTHING;

-- STUDENT: own-resource self-service only (read + initiate payment;
-- read + manage own skill profile + apply) — never MANAGE_FINANCE or
-- MANAGE_CAREER_LISTINGS, which stay admin/placement-officer ceilings.
INSERT INTO identity.role_permissions (role_id, permission_code)
SELECT r.id, p.code FROM identity.roles r
CROSS JOIN (VALUES ('VIEW_FINANCE'), ('INITIATE_PAYMENT'), ('VIEW_CAREER_LISTINGS'), ('MANAGE_OWN_SKILLS'), ('APPLY_TO_JOB')) AS p(code)
WHERE r.code = 'STUDENT'
ON CONFLICT (role_id, permission_code) DO NOTHING;

-- PLACEMENT_OFFICER: the new write-side placement-record code, matching
-- the matrix's "Placement records | ... | Tenant, full" row (kept distinct
-- from MANAGE_CAREER_LISTINGS, which covers jobs/skills/applications, for
-- the same granular-permissions reason V54 states).
INSERT INTO identity.role_permissions (role_id, permission_code)
SELECT r.id, p.code FROM identity.roles r
CROSS JOIN (VALUES ('MANAGE_PLACEMENT_RECORDS')) AS p(code)
WHERE r.code = 'PLACEMENT_OFFICER'
ON CONFLICT (role_id, permission_code) DO NOTHING;

-- SUPER_ADMIN: every new code, matching V55's own pattern of granting
-- SUPER_ADMIN each individual code explicitly rather than a wildcard.
INSERT INTO identity.role_permissions (role_id, permission_code)
SELECT r.id, p.code FROM identity.roles r
CROSS JOIN (VALUES ('VIEW_FINANCE'), ('INITIATE_PAYMENT'), ('VIEW_CAREER_LISTINGS'), ('MANAGE_OWN_SKILLS'),
                    ('APPLY_TO_JOB'), ('MANAGE_PLACEMENT_RECORDS')) AS p(code)
WHERE r.code = 'SUPER_ADMIN'
ON CONFLICT (role_id, permission_code) DO NOTHING;
