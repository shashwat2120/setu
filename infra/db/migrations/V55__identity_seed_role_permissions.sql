-- Sprint 5B Day 4: role -> permission grants. GUARDIAN is deliberately NOT
-- granted VIEW_GRADES/VIEW_ATTENDANCE/VIEW_SCHEDULE/VIEW_ACADEMIC_STATUS
-- here — granting those at the role level would let any guardian view ANY
-- student's data, defeating delegation entirely. A guardian's access to
-- those codes comes exclusively through an active
-- guardian_access_grants + guardian_access_permissions row, checked by
-- GuardianAccessService, never through this table (see the Day 4 report,
-- section D, and docs/architecture-notes/authorization-engine.md).
INSERT INTO identity.role_permissions (role_id, permission_code)
SELECT r.id, p.code FROM identity.roles r
CROSS JOIN (VALUES
    ('MANAGE_PLATFORM'), ('SUSPEND_USER'), ('MANAGE_FINANCE'),
    ('MANAGE_TENANT_USERS'), ('MANAGE_TENANT_ROLES'), ('MANAGE_ROLES_PLATFORM'),
    ('VIEW_TENANT_DATA'), ('MANAGE_GUARDIAN_ACCESS'),
    ('VIEW_GRADES'), ('VIEW_ATTENDANCE'), ('VIEW_SCHEDULE'), ('VIEW_ACADEMIC_STATUS'),
    ('VIEW_PROFILE'), ('VIEW_ENROLLMENTS'), ('VIEW_LEARNING_DATA'),
    ('VIEW_ASSIGNED_SECTIONS'), ('MANAGE_SECTION_GRADES'), ('MANAGE_ATTENDANCE'),
    ('MANAGE_CAREER_LISTINGS'), ('VIEW_PLACEMENT_RECORDS')
) AS p(code)
WHERE r.code = 'SUPER_ADMIN';

INSERT INTO identity.role_permissions (role_id, permission_code)
SELECT r.id, p.code FROM identity.roles r
CROSS JOIN (VALUES
    ('MANAGE_TENANT_USERS'), ('MANAGE_TENANT_ROLES'), ('VIEW_TENANT_DATA'),
    ('MANAGE_GUARDIAN_ACCESS'), ('SUSPEND_USER'),
    ('VIEW_GRADES'), ('VIEW_ATTENDANCE'), ('VIEW_SCHEDULE'), ('VIEW_ACADEMIC_STATUS'),
    ('VIEW_PROFILE')
) AS p(code)
WHERE r.code = 'COLLEGE_ADMIN';

INSERT INTO identity.role_permissions (role_id, permission_code)
SELECT r.id, p.code FROM identity.roles r
CROSS JOIN (VALUES
    ('VIEW_PROFILE'), ('VIEW_ASSIGNED_SECTIONS'), ('MANAGE_SECTION_GRADES'),
    ('MANAGE_ATTENDANCE'), ('VIEW_SCHEDULE')
) AS p(code)
WHERE r.code = 'FACULTY';

INSERT INTO identity.role_permissions (role_id, permission_code)
SELECT r.id, p.code FROM identity.roles r
CROSS JOIN (VALUES
    ('VIEW_PROFILE'), ('VIEW_ENROLLMENTS'), ('VIEW_GRADES'), ('VIEW_ATTENDANCE'),
    ('VIEW_SCHEDULE'), ('VIEW_LEARNING_DATA'), ('VIEW_ACADEMIC_STATUS')
) AS p(code)
WHERE r.code = 'STUDENT';

INSERT INTO identity.role_permissions (role_id, permission_code)
SELECT r.id, p.code FROM identity.roles r
CROSS JOIN (VALUES
    ('MANAGE_CAREER_LISTINGS'), ('VIEW_PLACEMENT_RECORDS'), ('VIEW_PROFILE')
) AS p(code)
WHERE r.code = 'PLACEMENT_OFFICER';

-- GUARDIAN: baseline only — see header note.
INSERT INTO identity.role_permissions (role_id, permission_code)
SELECT r.id, p.code FROM identity.roles r
CROSS JOIN (VALUES
    ('VIEW_PROFILE')
) AS p(code)
WHERE r.code = 'GUARDIAN';
