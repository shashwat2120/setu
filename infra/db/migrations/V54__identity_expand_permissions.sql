-- Sprint 5B Day 4: the granular permission catalog V9 deferred. V9's four
-- existing codes (VIEW_GRADES, VIEW_ATTENDANCE, VIEW_SCHEDULE,
-- VIEW_ACADEMIC_STATUS) are REUSED here, not duplicated — the same
-- permission code can be granted to a role (identity.role_permissions,
-- ownership-checked by the application layer) and separately delegated to a
-- guardian (guardian_access.guardian_access_permissions, delegation-checked)
-- — see docs/architecture-notes/authorization-engine.md for why one
-- permission catalog serves both grant mechanisms rather than duplicating
-- "VIEW_GRADES" and "VIEW_GRADES_OWN" as different codes.
INSERT INTO identity.permissions (code, resource, action) VALUES
    ('VIEW_PROFILE',            'person',          'VIEW_PROFILE'),
    ('VIEW_ENROLLMENTS',        'enrollment',      'VIEW_ENROLLMENTS'),
    ('VIEW_LEARNING_DATA',      'learning',        'VIEW_LEARNING_DATA'),
    ('VIEW_ASSIGNED_SECTIONS',  'enrollment',      'VIEW_ASSIGNED_SECTIONS'),
    ('MANAGE_SECTION_GRADES',   'academic_record', 'MANAGE_SECTION_GRADES'),
    ('MANAGE_ATTENDANCE',       'attendance',      'MANAGE_ATTENDANCE'),
    ('MANAGE_TENANT_USERS',     'identity',        'MANAGE_TENANT_USERS'),
    ('MANAGE_TENANT_ROLES',     'identity',        'MANAGE_TENANT_ROLES'),
    ('MANAGE_ROLES_PLATFORM',   'identity',        'MANAGE_ROLES_PLATFORM'),
    ('VIEW_TENANT_DATA',        'institution',     'VIEW_TENANT_DATA'),
    ('MANAGE_GUARDIAN_ACCESS',  'guardian_access', 'MANAGE_GUARDIAN_ACCESS'),
    ('MANAGE_CAREER_LISTINGS',  'career',          'MANAGE_CAREER_LISTINGS'),
    ('VIEW_PLACEMENT_RECORDS',  'career',          'VIEW_PLACEMENT_RECORDS'),
    ('SUSPEND_USER',            'identity',        'SUSPEND_USER'),
    ('MANAGE_FINANCE',          'finance',         'MANAGE_FINANCE'),
    ('MANAGE_PLATFORM',         'platform',        'MANAGE_PLATFORM');
