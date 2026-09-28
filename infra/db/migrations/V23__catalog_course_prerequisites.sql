CREATE TABLE catalog.course_prerequisites (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id               UUID NOT NULL REFERENCES institution.colleges (id),
    course_id               UUID NOT NULL REFERENCES catalog.courses (id),
    prerequisite_course_id  UUID NOT NULL REFERENCES catalog.courses (id),
    rule_type               TEXT NOT NULL CHECK (rule_type IN ('REQUIRED','RECOMMENDED','COREQUISITE')),
    minimum_grade           TEXT NULL,
    -- Row-level self-loop guard. Full cycle prevention across the whole
    -- graph is an application-layer check at write time (docs/erd/02-academic.md) —
    -- a CHECK constraint cannot walk a graph.
    CONSTRAINT chk_course_prerequisites_no_self_loop CHECK (course_id <> prerequisite_course_id)
);
CREATE INDEX idx_course_prerequisites_course ON catalog.course_prerequisites (course_id);
CREATE INDEX idx_course_prerequisites_prereq ON catalog.course_prerequisites (prerequisite_course_id);
