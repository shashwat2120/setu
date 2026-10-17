-- A service's copy of who is enrolled where now also knows which enrollments are COMPLETED (their final grade was published) as opposed to
-- dropped: both are "not active", but a completed enrollment can still be graded (late components, corrections of the final grade) and a
-- dropped one cannot. Kept current by Academic's EnrollmentCompleted event.
DO $$
DECLARE s TEXT;
BEGIN
    FOREACH s IN ARRAY ARRAY['learning', 'assessment', 'attendance', 'notification'] LOOP
        EXECUTE format('ALTER TABLE %I.rm_enrollments ADD COLUMN completed BOOLEAN NOT NULL DEFAULT FALSE', s);
        -- the initial content comes from the owning schema, as in V81 / V85 (one database until 6B.2 gives each service its own)
        EXECUTE format('UPDATE %I.rm_enrollments r SET completed = TRUE FROM enrollment.enrollments e WHERE e.id = r.enrollment_id AND e.status = ''COMPLETED''', s);
    END LOOP;
END $$;
