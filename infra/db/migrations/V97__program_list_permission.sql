-- A placement officer sets which programs a job is open to, so they must be able to read the college's program list (name and code only, nothing about
-- any student). VIEW_TENANT_DATA would also open everything else tenant-wide, so this is a narrow permission of its own.
INSERT INTO identity.permissions (code, resource, action) VALUES
    ('VIEW_PROGRAMS', 'catalog', 'VIEW_PROGRAMS')
ON CONFLICT (code) DO NOTHING;

INSERT INTO identity.role_permissions (role_id, permission_code)
SELECT r.id, p.code FROM identity.roles r
CROSS JOIN (VALUES ('VIEW_PROGRAMS')) AS p(code)
WHERE r.code IN ('PLACEMENT_OFFICER', 'COLLEGE_ADMIN', 'SUPER_ADMIN')
ON CONFLICT (role_id, permission_code) DO NOTHING;
