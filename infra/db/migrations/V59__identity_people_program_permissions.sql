-- Sprint 5C Day 6: granular permissions for the people/program admin
-- operations this day adds — none of the existing catalog (V9/V54) covers
-- student admission, faculty onboarding, or program/curriculum management
-- specifically. Granted to SUPER_ADMIN + COLLEGE_ADMIN, matching every
-- other tenant-administration permission's grant pattern (V55).
INSERT INTO identity.permissions (code, resource, action) VALUES
    ('MANAGE_STUDENTS', 'directory', 'MANAGE_STUDENTS'),
    ('MANAGE_FACULTY', 'directory', 'MANAGE_FACULTY'),
    ('MANAGE_GUARDIANS', 'directory', 'MANAGE_GUARDIANS'),
    ('MANAGE_PROGRAMS', 'catalog', 'MANAGE_PROGRAMS'),
    ('MANAGE_STUDENT_PROGRAMS', 'enrollment', 'MANAGE_STUDENT_PROGRAMS');

INSERT INTO identity.role_permissions (role_id, permission_code)
SELECT r.id, p.code FROM identity.roles r
CROSS JOIN (VALUES
    ('MANAGE_STUDENTS'), ('MANAGE_FACULTY'), ('MANAGE_GUARDIANS'),
    ('MANAGE_PROGRAMS'), ('MANAGE_STUDENT_PROGRAMS')
) AS p(code)
WHERE r.code IN ('SUPER_ADMIN', 'COLLEGE_ADMIN');
