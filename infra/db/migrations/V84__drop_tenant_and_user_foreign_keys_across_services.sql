-- 6B.2: V83 dropped the foreign keys between services' business tables. What is left are the references every table carries to the platform:
-- the tenant (institution.colleges, owned by Academic) and the user (identity.users, owned by Identity). With the services on separate
-- databases those are ids like any other, so a table of an extracted service may not be tied to a table of another service by a constraint
-- at all (docs/contracts/data-ownership.md section 2).
--
-- Rule, not a list: every foreign key whose child table is in the schema of a service that runs on its own and whose parent table is in
-- another service's schema is dropped, whatever it is called. The column and its index stay. Constraints inside one service (Academic's
-- own catalog, enrollment, directory ... schemas) stay, and so do those of modules still in the core (record, finance, career, risk,
-- notification), which go when those modules are extracted.
--
--   Identity: identity
--   Academic: institution, catalog, enrollment, directory, guardian_access
--   Learning: learning     Assessment: assessment     Attendance: attendance

DO $$
DECLARE
    fk      RECORD;
    dropped INT := 0;
BEGIN
    FOR fk IN
        WITH owner(schema_name, service) AS (VALUES
            ('identity', 'Identity'),
            ('institution', 'Academic'), ('catalog', 'Academic'), ('enrollment', 'Academic'), ('directory', 'Academic'), ('guardian_access', 'Academic'),
            ('learning', 'Learning'), ('assessment', 'Assessment'), ('attendance', 'Attendance'),
            ('record', 'Assessment'), ('risk', 'StudentSuccess'), ('career', 'Career'), ('finance', 'Finance'), ('notification', 'Notification'))
        SELECT k.conname, k.conrelid::regclass AS child_table
          FROM pg_constraint k
          JOIN pg_class c ON c.oid = k.conrelid JOIN pg_namespace cn ON cn.oid = c.relnamespace
          JOIN pg_class p ON p.oid = k.confrelid JOIN pg_namespace pn ON pn.oid = p.relnamespace
          JOIN owner co ON co.schema_name = cn.nspname
          JOIN owner po ON po.schema_name = pn.nspname
         WHERE k.contype = 'f'
           AND co.service <> po.service
           AND cn.nspname IN ('identity', 'institution', 'catalog', 'enrollment', 'directory', 'guardian_access', 'learning', 'assessment', 'attendance')
    LOOP
        EXECUTE format('ALTER TABLE %s DROP CONSTRAINT %I', fk.child_table, fk.conname);
        dropped := dropped + 1;
    END LOOP;
    RAISE NOTICE 'dropped % tenant/user foreign keys across services', dropped;
END
$$;
