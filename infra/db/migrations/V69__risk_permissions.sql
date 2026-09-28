-- Day 13 §Student Success Engine: no risk-domain permission code existed
-- anywhere before this migration (confirmed by grep across every prior
-- migration) — this is the first seed for the "Risk score / factors" and
-- "Recommendation / intervention" rows of docs/authorization-matrix.md.
--
-- Matches the matrix exactly:
--   Risk score / factors       | SUPER_ADMIN: Platform | COLLEGE_ADMIN: Tenant, read
--                               | FACULTY: Own advisees, read | STUDENT: Own, read
--                               | GUARDIAN: Read own (if granted — narrow default)
--   Recommendation/intervention| SUPER_ADMIN: Platform | COLLEGE_ADMIN: Tenant, full
--                               | FACULTY: Own advisees, create/manage | STUDENT: Own, read
--
-- Two-tier shape (VIEW_RISK_SCORE / _ALL_SECTIONS, MANAGE_INTERVENTIONS /
-- _ALL_SECTIONS) follows the V65/V67 precedent exactly: the base code is
-- role-granted broadly to FACULTY/STUDENT and narrowed per-request via
-- ResourceRef ownership/assignment (own student, or "advisee" — see
-- RiskAccessSupport's javadoc for why "advisee" reuses the existing
-- faculty-assigned-to-section relationship rather than the schema's
-- unpopulated ADVISOR class_faculty role); the _ALL_SECTIONS code is the
-- tenant-wide, non-narrowed ceiling for SUPER_ADMIN/COLLEGE_ADMIN.
--
-- One combined MANAGE_INTERVENTIONS code covers both recommendation
-- accept/dismiss and intervention create/manage/complete — the matrix
-- lists them as a single "Recommendation / intervention" row, and V54's
-- own stated rule is granular-but-not-redundant permissions, not one code
-- per individual state transition.
INSERT INTO identity.permissions (code, resource, action) VALUES
    ('VIEW_RISK_SCORE', 'risk', 'VIEW_RISK_SCORE'),
    ('VIEW_RISK_SCORE_ALL_SECTIONS', 'risk', 'VIEW_RISK_SCORE_ALL_SECTIONS'),
    ('MANAGE_INTERVENTIONS', 'risk', 'MANAGE_INTERVENTIONS'),
    ('MANAGE_INTERVENTIONS_ALL_SECTIONS', 'risk', 'MANAGE_INTERVENTIONS_ALL_SECTIONS')
ON CONFLICT (code) DO NOTHING;

-- FACULTY: own-advisees scope for both viewing risk data and managing
-- recommendations/interventions — narrowed per-request via ResourceRef,
-- never a bypass (same idiom as MANAGE_ASSESSMENTS since Day 11).
INSERT INTO identity.role_permissions (role_id, permission_code)
SELECT r.id, p.code FROM identity.roles r
CROSS JOIN (VALUES ('VIEW_RISK_SCORE'), ('MANAGE_INTERVENTIONS')) AS p(code)
WHERE r.code = 'FACULTY'
ON CONFLICT (role_id, permission_code) DO NOTHING;

-- STUDENT: own-record read only — never MANAGE_INTERVENTIONS (a student
-- cannot manage their own intervention/recommendation per the matrix's
-- "Own, read" cell).
INSERT INTO identity.role_permissions (role_id, permission_code)
SELECT r.id, p.code FROM identity.roles r
CROSS JOIN (VALUES ('VIEW_RISK_SCORE')) AS p(code)
WHERE r.code = 'STUDENT'
ON CONFLICT (role_id, permission_code) DO NOTHING;

-- COLLEGE_ADMIN and SUPER_ADMIN: the tenant-wide (and, for SUPER_ADMIN,
-- cross-tenant per the existing platform-permission mechanism) ceiling —
-- read risk data and fully manage recommendations/interventions across
-- the whole tenant, not narrowed by advisee assignment.
INSERT INTO identity.role_permissions (role_id, permission_code)
SELECT r.id, p.code FROM identity.roles r
CROSS JOIN (VALUES ('VIEW_RISK_SCORE_ALL_SECTIONS'), ('MANAGE_INTERVENTIONS_ALL_SECTIONS')) AS p(code)
WHERE r.code IN ('SUPER_ADMIN', 'COLLEGE_ADMIN')
ON CONFLICT (role_id, permission_code) DO NOTHING;
