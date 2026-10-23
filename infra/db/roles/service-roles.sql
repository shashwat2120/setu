-- One database login per service, able to touch only the schemas that service owns.
--
-- Until now every service connected to the shared database as the same user, so "each table has one owner" was kept by convention and by tests, and nothing in the
-- database stopped a service from reading another's tables (the identity service's accounts and sessions, say). With these roles the database itself refuses: a service that
-- reaches into a schema it does not own gets "permission denied" (SQLSTATE 42501), and none of them can change the schema at all (the core, which runs the migrations as
-- the database owner, is the only one that can).
--
-- What each service gets (mirrors backend/src/test/resources/table-owners.txt; ServiceDatabaseRolesTests checks the two agree):
--   setu_identity     identity
--   setu_academic     catalog, directory, enrollment, guardian_access, institution
--   setu_learning     learning
--   setu_assessment   assessment            (the record schema is Assessment's on paper but Record still runs in the core, so it is not granted)
--   setu_attendance   attendance
--   setu_notification notification
-- and, for every one of them, the cross-cutting governance tables that every process writes: the outbox (read, add, mark published), the audit log (read and append,
-- never change or remove), and idempotency keys. The gateway and the AI service have no database at all.
-- plus the one framework table in public that every process writes (Spring Modulith's event_publication).
-- Not granted to any service role: finance, career, risk and record (the core only), the migration history, and anything else in the public schema.
--
-- Safe to run again at any time: it converges (grants that are no longer in the table below are taken back, tables added by later migrations are covered by default
-- privileges). Run it as the role that owns the schemas, after the migrations. The password comes from the session setting setu.role_password (psql: -c "SET setu.role_password = '...'"
-- before -f), or the development default.

DO $roles$
DECLARE
    owner_role constant text := current_user;
    password   constant text := coalesce(nullif(current_setting('setu.role_password', true), ''), 'setu_dev_password');
    service    record;
    role_name  text;
    schema_name text;
    framework_table text;
BEGIN
    FOR service IN
        SELECT * FROM (VALUES
            ('identity',     ARRAY['identity']),
            ('academic',     ARRAY['catalog', 'directory', 'enrollment', 'guardian_access', 'institution']),
            ('learning',     ARRAY['learning']),
            ('assessment',   ARRAY['assessment']),
            ('attendance',   ARRAY['attendance']),
            ('notification', ARRAY['notification'])
        ) AS s(name, schemas)
    LOOP
        role_name := 'setu_' || service.name;

        IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = role_name) THEN
            EXECUTE format('CREATE ROLE %I LOGIN PASSWORD %L NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT', role_name, password);
        ELSE
            EXECUTE format('ALTER ROLE %I LOGIN PASSWORD %L NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT', role_name, password);
        END IF;
        EXECUTE format('GRANT CONNECT ON DATABASE %I TO %I', current_database(), role_name);

        -- start from nothing in every schema of this database, so a grant dropped from the table above is taken back and nothing lingers
        FOR schema_name IN
            SELECT nspname FROM pg_namespace WHERE nspname NOT LIKE 'pg\_%' AND nspname NOT IN ('information_schema', 'public')
        LOOP
            EXECUTE format('REVOKE ALL ON ALL TABLES IN SCHEMA %I FROM %I', schema_name, role_name);
            EXECUTE format('REVOKE ALL ON ALL SEQUENCES IN SCHEMA %I FROM %I', schema_name, role_name);
            EXECUTE format('REVOKE ALL ON SCHEMA %I FROM %I', schema_name, role_name);
            EXECUTE format('ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA %I REVOKE ALL ON TABLES FROM %I', owner_role, schema_name, role_name);
            EXECUTE format('ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA %I REVOKE ALL ON SEQUENCES FROM %I', owner_role, schema_name, role_name);
        END LOOP;

        -- the schemas this service owns: ordinary data access, no schema changes
        FOREACH schema_name IN ARRAY service.schemas LOOP
            EXECUTE format('GRANT USAGE ON SCHEMA %I TO %I', schema_name, role_name);
            EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA %I TO %I', schema_name, role_name);
            EXECUTE format('GRANT USAGE, SELECT, UPDATE ON ALL SEQUENCES IN SCHEMA %I TO %I', schema_name, role_name);
            EXECUTE format('ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA %I GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO %I', owner_role, schema_name, role_name);
            EXECUTE format('ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA %I GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO %I', owner_role, schema_name, role_name);
        END LOOP;

        -- the platform's cross-cutting tables, with only what each is for
        EXECUTE format('GRANT USAGE ON SCHEMA governance TO %I', role_name);
        EXECUTE format('GRANT SELECT, INSERT, UPDATE ON governance.outbox_events TO %I', role_name);
        EXECUTE format('GRANT SELECT, INSERT ON governance.audit_logs TO %I', role_name);
        EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON governance.idempotency_keys TO %I', role_name);

        -- the public schema holds the migration history (the core's alone) and one table every process needs: Spring Modulith's log of application events it has
        -- published and not yet seen handled, which it writes itself from any process that publishes one (found when the attendance service, switched to its own
        -- login, was refused it)
        EXECUTE format('REVOKE ALL ON ALL TABLES IN SCHEMA public FROM %I', role_name);
        FOREACH framework_table IN ARRAY ARRAY['event_publication', 'event_publication_archive'] LOOP
            IF to_regclass('public.' || framework_table) IS NOT NULL THEN
                EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON public.%I TO %I', framework_table, role_name);
            END IF;
        END LOOP;
    END LOOP;
END
$roles$;
