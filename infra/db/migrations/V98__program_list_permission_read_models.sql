-- Every service keeps its own copy of the role-to-permission matrix (V80, V81, V85), and it changes only by migration: a change to
-- identity.role_permissions must change each copy in the same migration, and the read-model parity tests fail if they drift.
DO $migration$
DECLARE
    s text;
BEGIN
    FOREACH s IN ARRAY ARRAY['institution', 'learning', 'assessment', 'attendance', 'notification'] LOOP
        EXECUTE format('INSERT INTO %I.rm_role_permissions (role_code, permission_code)
            SELECT r.code, rp.permission_code FROM identity.role_permissions rp JOIN identity.roles r ON r.id = rp.role_id
            WHERE rp.permission_code = ''VIEW_PROGRAMS'' ON CONFLICT DO NOTHING', s);
    END LOOP;
END
$migration$;
