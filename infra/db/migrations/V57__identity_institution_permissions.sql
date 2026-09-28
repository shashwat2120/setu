-- Sprint 5C Day 5: two new granular permissions for institution/academic
-- structure CRUD ("use granular permissions", not a single catch-all).
-- College STATUS transitions (suspend/reactivate/archive) intentionally use
-- the existing MANAGE_PLATFORM permission instead — docs/state-machines.md
-- #4 marks ACTIVE -> SUSPENDED as "platform-level, SUPER_ADMIN only", and a
-- tenant should never be able to suspend or archive itself, so status
-- transitions are not part of MANAGE_INSTITUTION's grant here.
INSERT INTO identity.permissions (code, resource, action) VALUES
    ('MANAGE_INSTITUTION', 'institution', 'MANAGE_INSTITUTION'),
    ('MANAGE_ACADEMIC_CALENDAR', 'catalog', 'MANAGE_ACADEMIC_CALENDAR');

INSERT INTO identity.role_permissions (role_id, permission_code)
SELECT r.id, p.code FROM identity.roles r
CROSS JOIN (VALUES ('MANAGE_INSTITUTION'), ('MANAGE_ACADEMIC_CALENDAR')) AS p(code)
WHERE r.code IN ('SUPER_ADMIN', 'COLLEGE_ADMIN');
