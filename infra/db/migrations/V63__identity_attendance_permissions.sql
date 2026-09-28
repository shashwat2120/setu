-- MANAGE_ATTENDANCE already exists (V54/V55, Day 4) — granted broadly to
-- SUPER_ADMIN and FACULTY (tenant-wide, not scoped to a specific section)
-- but never to COLLEGE_ADMIN. This ON CONFLICT DO NOTHING is a defensive
-- no-op against that pre-existing row, not a fresh definition.
INSERT INTO identity.permissions (code, resource, action) VALUES
    ('MANAGE_ATTENDANCE', 'attendance', 'MANAGE_ATTENDANCE')
ON CONFLICT (code) DO NOTHING;

-- Day 9 §7: FACULTY's existing MANAGE_ATTENDANCE grant is tenant-wide, but
-- the invariant "a faculty member must control attendance only for their
-- OWN assigned section" needs per-resource assignment scoping the
-- authorization engine can only apply when the ResourceRef declares an
-- assigned set — which would incorrectly also deny COLLEGE_ADMIN (not
-- SUPER_ADMIN, so not exempt from the assignment check). A separate,
-- admin-only permission is the correct fix: the application layer checks
-- real assignment data first (attendance.AttendanceSessionService's
-- assigned-faculty-or-admin idiom) and only falls back to THIS permission
-- — never the FACULTY-held MANAGE_ATTENDANCE — for the "any section in
-- this tenant" admin path.
INSERT INTO identity.permissions (code, resource, action) VALUES
    ('MANAGE_ATTENDANCE_ALL_SECTIONS', 'attendance', 'MANAGE_ATTENDANCE_ALL_SECTIONS')
ON CONFLICT (code) DO NOTHING;

INSERT INTO identity.role_permissions (role_id, permission_code)
SELECT r.id, p.code
FROM identity.roles r
CROSS JOIN (VALUES
    ('MANAGE_ATTENDANCE_ALL_SECTIONS')
) AS p(code)
WHERE r.code IN ('SUPER_ADMIN', 'COLLEGE_ADMIN')
ON CONFLICT (role_id, permission_code) DO NOTHING;
