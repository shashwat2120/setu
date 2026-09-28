INSERT INTO identity.permissions (code, resource, action) VALUES
    ('MANAGE_ENROLLMENTS', 'enrollment', 'MANAGE_ENROLLMENTS'),
    ('MANAGE_ENROLLMENT_OVERRIDES', 'enrollment', 'MANAGE_ENROLLMENT_OVERRIDES'),
    ('MANAGE_WAITLIST', 'enrollment', 'MANAGE_WAITLIST');

INSERT INTO identity.role_permissions (role_id, permission_code)
SELECT r.id, p.code
FROM identity.roles r
CROSS JOIN (VALUES
    ('MANAGE_ENROLLMENTS'),
    ('MANAGE_ENROLLMENT_OVERRIDES'),
    ('MANAGE_WAITLIST')
) AS p(code)
WHERE r.code IN ('SUPER_ADMIN', 'COLLEGE_ADMIN');
