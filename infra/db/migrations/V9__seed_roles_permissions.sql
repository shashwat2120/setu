INSERT INTO identity.roles (code, name, scope_type) VALUES
    ('SUPER_ADMIN', 'Super Admin', 'PLATFORM'),
    ('COLLEGE_ADMIN', 'College Admin', 'TENANT'),
    ('FACULTY', 'Faculty', 'TENANT'),
    ('STUDENT', 'Student', 'TENANT'),
    ('PLACEMENT_OFFICER', 'Placement Officer', 'TENANT'),
    ('GUARDIAN', 'Guardian', 'TENANT');

-- Intentionally minimal: only the guardian-scope permission codes that are
-- explicitly named in docs/erd/ERD.md (guardian_access_permissions) and
-- docs/authorization-matrix.md. The full RBAC permission catalog is Sprint
-- 5B (authorization implementation) work, not invented here — seeding a
-- speculative full permission matrix now would violate "do not invent new
-- columns or relationships" applied to data, not just schema.
INSERT INTO identity.permissions (code, resource, action) VALUES
    ('VIEW_GRADES', 'academic_record', 'VIEW_GRADES'),
    ('VIEW_ATTENDANCE', 'attendance', 'VIEW_ATTENDANCE'),
    ('VIEW_SCHEDULE', 'enrollment', 'VIEW_SCHEDULE'),
    ('VIEW_ACADEMIC_STATUS', 'academic_record', 'VIEW_ACADEMIC_STATUS');
