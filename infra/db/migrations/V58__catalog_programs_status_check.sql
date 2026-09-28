-- Sprint 5C Day 6 gap-closure, disclosed per the Day 6 report: V19 defined
-- catalog.programs.status as free TEXT with no CHECK constraint at all — the
-- only status column anywhere in the schema without one (every other
-- lifecycle column, colleges/users/sessions/academic_years/student_profiles/
-- faculty_profiles/student_programs, has an explicit CHECK). No
-- docs/state-machines.md entry exists for Program either. Rather than invent
-- an elaborate lifecycle nothing in the canonical docs describes, this adds
-- the smallest defensible vocabulary matching the migration's own existing
-- default ('ACTIVE') plus one terminal-ish counterpart a program catalog
-- genuinely needs (stop admitting new students without deleting history) —
-- see ProgramService's own javadoc for the transition rule.
--
-- Safe to add now: no data exists yet in a fresh/prior schema besides the
-- literal 'ACTIVE' default, so this cannot violate any existing row.
ALTER TABLE catalog.programs
    ADD CONSTRAINT chk_programs_status CHECK (status IN ('ACTIVE', 'INACTIVE'));
