-- Day 10 §7/§15: LMS content (modules/lessons/resources) authorization
-- matrix row is "COLLEGE_ADMIN: Tenant" with no explicit "read" qualifier,
-- unlike the two rows below it which explicitly say "Tenant, read" — read
-- as full tenant-wide management (the doc's convention elsewhere marks
-- read-only scopes explicitly), disclosed in the Day 10 report. Faculty's
-- own-assigned-section access never checks a permission at all (the
-- established assigned-faculty-or-admin idiom, same as Day 9's
-- MANAGE_ATTENDANCE_ALL_SECTIONS) — this permission exists solely for the
-- admin fallback path.
INSERT INTO identity.permissions (code, resource, action) VALUES
    ('MANAGE_LEARNING_CONTENT', 'learning', 'MANAGE_LEARNING_CONTENT')
ON CONFLICT (code) DO NOTHING;

INSERT INTO identity.role_permissions (role_id, permission_code)
SELECT r.id, p.code FROM identity.roles r
CROSS JOIN (VALUES ('MANAGE_LEARNING_CONTENT')) AS p(code)
WHERE r.code IN ('SUPER_ADMIN', 'COLLEGE_ADMIN')
ON CONFLICT (role_id, permission_code) DO NOTHING;

-- Day 10 §7: "Assignment/submission" row caps COLLEGE_ADMIN at "Tenant,
-- read" (no manage) — a genuinely different admin ceiling than LMS content
-- above, so a SEPARATE, narrower permission is required: granted only to
-- SUPER_ADMIN ("Platform, full"), never COLLEGE_ADMIN. Admin/platform
-- read-only access to assignments/submissions reuses the existing
-- VIEW_TENANT_DATA permission (already held by COLLEGE_ADMIN and
-- SUPER_ADMIN) rather than a new code.
INSERT INTO identity.permissions (code, resource, action) VALUES
    ('MANAGE_ASSIGNMENTS', 'learning', 'MANAGE_ASSIGNMENTS')
ON CONFLICT (code) DO NOTHING;

INSERT INTO identity.role_permissions (role_id, permission_code)
SELECT r.id, p.code FROM identity.roles r
CROSS JOIN (VALUES ('MANAGE_ASSIGNMENTS')) AS p(code)
WHERE r.code = 'SUPER_ADMIN'
ON CONFLICT (role_id, permission_code) DO NOTHING;

-- Day 10 §concurrency: learning_progress rows are repeatedly UPDATEd (a
-- student's own evolving progress, and can race a concurrent update to the
-- same row) — needs the same optimistic-lock guard attendance_records
-- gained in Day 9, for the same reason. assignment_submissions is
-- create-only in Day 10 (no edit/correction workflow yet — grading is
-- Day 11), so the DB unique constraint alone is sufficient there; no
-- version column added.
ALTER TABLE learning.learning_progress ADD COLUMN version INT NOT NULL DEFAULT 0;
