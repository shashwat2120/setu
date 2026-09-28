-- Sprint 5C Day 7: granular permissions for curriculum/course/section/
-- faculty-assignment/facility management — none of the existing catalog
-- (V9/V54/V57/V59) covers these specifically.
INSERT INTO identity.permissions (code, resource, action) VALUES
    ('MANAGE_CURRICULUM', 'catalog', 'MANAGE_CURRICULUM'),
    ('MANAGE_COURSES', 'catalog', 'MANAGE_COURSES'),
    ('MANAGE_SECTIONS', 'enrollment', 'MANAGE_SECTIONS'),
    ('MANAGE_FACULTY_ASSIGNMENTS', 'enrollment', 'MANAGE_FACULTY_ASSIGNMENTS'),
    ('MANAGE_FACILITIES', 'catalog', 'MANAGE_FACILITIES');

INSERT INTO identity.role_permissions (role_id, permission_code)
SELECT r.id, p.code FROM identity.roles r
CROSS JOIN (VALUES
    ('MANAGE_CURRICULUM'), ('MANAGE_COURSES'), ('MANAGE_SECTIONS'),
    ('MANAGE_FACULTY_ASSIGNMENTS'), ('MANAGE_FACILITIES')
) AS p(code)
WHERE r.code IN ('SUPER_ADMIN', 'COLLEGE_ADMIN');
