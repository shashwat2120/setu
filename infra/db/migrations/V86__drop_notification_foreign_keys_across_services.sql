-- 6B.3: the Notification/Realtime service now runs on its own (docs/contracts/notification-service.md), so its tables join the rule V84 set for
-- the other extracted services: no foreign key from a notification table to another service's table. What is left is the tenant
-- (institution.colleges, Academic's) and the user (identity.users, Identity's) that each notification carries as plain ids. The recipient of a
-- notification is resolved from the service's own copy of Academic's data when the event is handled, not by a constraint.
--
-- Same rule as V84, applied to the one schema that was added to the extracted set: the constraints go, the columns and indexes stay.

DO $$
DECLARE
    fk      RECORD;
    dropped INT := 0;
BEGIN
    FOR fk IN
        SELECT k.conname, k.conrelid::regclass AS child_table
          FROM pg_constraint k
          JOIN pg_class c ON c.oid = k.conrelid JOIN pg_namespace cn ON cn.oid = c.relnamespace
          JOIN pg_class p ON p.oid = k.confrelid JOIN pg_namespace pn ON pn.oid = p.relnamespace
         WHERE k.contype = 'f'
           AND cn.nspname = 'notification'
           AND pn.nspname <> 'notification'
    LOOP
        EXECUTE format('ALTER TABLE %s DROP CONSTRAINT %I', fk.child_table, fk.conname);
        dropped := dropped + 1;
    END LOOP;
    RAISE NOTICE 'dropped % notification foreign keys across services', dropped;
END
$$;
