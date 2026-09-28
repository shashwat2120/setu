-- Day 11 §concurrency (transaction-concurrency-matrix.md scenario 6): the
-- FOR UPDATE lock on the current row is the primary guard against two
-- concurrent corrections both computing the same next version, but a
-- composite unique constraint is a cheaper-to-reason-about second guard
-- (the doc's own words) — especially for the FIRST-ever grade on a pair,
-- where there is no existing row to lock at all.
ALTER TABLE assessment.grades
    ADD CONSTRAINT uq_grades_enrollment_component_version UNIQUE (enrollment_id, component_id, version);

-- Day 11 §authorization: per the explicit instruction NOT to blindly repeat
-- Day 10's "assignment bypasses permission" idiom — assignment now defines
-- RESOURCE SCOPE (via ResourceRef.assignedTo, the authorization engine's own
-- mechanism, used for real for the first time), while a genuine permission
-- grant still gates the ACTION itself. FACULTY holds MANAGE_ASSESSMENTS
-- tenant-wide at the role level, but every real request additionally
-- narrows via the resource's assigned-faculty set — an unassigned faculty
-- member holding the same role-level permission is still denied by the
-- engine's own ownership check, not by a hand-rolled bypass.
INSERT INTO identity.permissions (code, resource, action) VALUES
    ('MANAGE_ASSESSMENTS', 'assessment', 'MANAGE_ASSESSMENTS'),
    ('MANAGE_ASSESSMENTS_ALL_SECTIONS', 'assessment', 'MANAGE_ASSESSMENTS_ALL_SECTIONS'),
    ('MANAGE_SECTION_GRADES_ALL_SECTIONS', 'academic_record', 'MANAGE_SECTION_GRADES_ALL_SECTIONS')
ON CONFLICT (code) DO NOTHING;

INSERT INTO identity.role_permissions (role_id, permission_code)
SELECT r.id, p.code FROM identity.roles r
CROSS JOIN (VALUES ('MANAGE_ASSESSMENTS')) AS p(code)
WHERE r.code = 'FACULTY'
ON CONFLICT (role_id, permission_code) DO NOTHING;

-- Matches the authorization matrix: assessment row gives COLLEGE_ADMIN
-- "Tenant, approve" (read as tenant-wide manage, the same convention
-- established in Day 10 for LMS content's unqualified "Tenant" cell) and
-- grade row gives COLLEGE_ADMIN "Tenant, correct with audit" — both a
-- genuinely higher ceiling than the section-scoped faculty grant, so both
-- go to SUPER_ADMIN and COLLEGE_ADMIN, never narrowed by assignment.
INSERT INTO identity.role_permissions (role_id, permission_code)
SELECT r.id, p.code FROM identity.roles r
CROSS JOIN (VALUES ('MANAGE_ASSESSMENTS_ALL_SECTIONS'), ('MANAGE_SECTION_GRADES_ALL_SECTIONS')) AS p(code)
WHERE r.code IN ('SUPER_ADMIN', 'COLLEGE_ADMIN')
ON CONFLICT (role_id, permission_code) DO NOTHING;
