-- 6B.2: one owner per table means no foreign key may cross a service boundary. These 17 constraints (frozen until now in
-- backend/src/test/resources/cross-service-foreign-keys.txt, see docs/contracts/data-ownership.md) tie a table to another service's table.
-- They go; the reference stays as a plain column, kept honest by the owning service's check at write time (AcademicDirectory and
-- the owner's read model) and by the reconciliation queries in docs/contracts/data-ownership.md. The column indexes stay.
-- The foreign keys of Record, Career, Finance and Risk stay: those modules still run in the core, and go when they are extracted.
-- Generated from the frozen list; matching is by (child table, parent table), so it does not depend on constraint names.

DO $$
DECLARE
    pair RECORD;
    fk   RECORD;
    dropped INT := 0;
BEGIN
    FOR pair IN
        SELECT * FROM (VALUES
        ('directory.faculty_profiles', 'identity.persons'),
        ('directory.guardians', 'identity.persons'),
        ('directory.student_profiles', 'identity.persons'),
        ('guardian_access.guardian_access_permissions', 'identity.permissions'),
        ('assessment.assessment_attempts', 'directory.student_profiles'),
        ('assessment.assessments', 'enrollment.course_sections'),
        ('assessment.grades', 'enrollment.enrollments'),
        ('attendance.attendance_records', 'enrollment.enrollments'),
        ('attendance.attendance_sessions', 'catalog.meeting_patterns'),
        ('attendance.attendance_sessions', 'enrollment.course_sections'),
        ('attendance.faculty_attendance', 'directory.faculty_profiles'),
        ('attendance.faculty_attendance_regularisations', 'directory.faculty_profiles'),
        ('learning.assignment_submissions', 'directory.student_profiles'),
        ('learning.assignments', 'enrollment.course_sections'),
        ('learning.course_modules', 'enrollment.course_sections'),
        ('learning.learning_progress', 'directory.student_profiles'),
        ('learning.assignment_submissions', 'assessment.grades')
        ) AS t(child, parent)
    LOOP
        FOR fk IN
            SELECT c.conname, c.conrelid::regclass AS child_table
              FROM pg_constraint c
             WHERE c.contype = 'f'
               AND c.conrelid = pair.child::regclass
               AND c.confrelid = pair.parent::regclass
        LOOP
            EXECUTE format('ALTER TABLE %s DROP CONSTRAINT %I', fk.child_table, fk.conname);
            dropped := dropped + 1;
        END LOOP;
    END LOOP;
    RAISE NOTICE 'dropped % cross-service foreign keys', dropped;
END
$$;
