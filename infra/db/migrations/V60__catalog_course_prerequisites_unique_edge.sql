-- Sprint 5C Day 7 gap-closure, disclosed per the Day 7 report: V23 already
-- enforces the self-loop guard (chk_course_prerequisites_no_self_loop) but
-- never added a uniqueness constraint on the edge itself — two identical
-- (course_id, prerequisite_course_id) rows were always possible. Day 7
-- explicitly requires "duplicate edge is rejected" and "prefer adding
-- missing constraints... rather than recreating tables" — this is the
-- smallest possible fix, matching the pattern every other edge/junction
-- table in the schema already uses (see uq_curriculum_requirement_courses,
-- V22). Full cycle prevention (A->B->A, indirect cycles) remains an
-- application-level graph walk — a CHECK/UNIQUE constraint cannot express
-- that, as V23's own header comment already says.
ALTER TABLE catalog.course_prerequisites
    ADD CONSTRAINT uq_course_prerequisites_edge UNIQUE (course_id, prerequisite_course_id);
