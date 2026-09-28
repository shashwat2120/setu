-- tenant_id added per docs/erd-audit-2.md finding A — this joins two
-- independently tenant-owned entities (requirement via curriculum/program,
-- course directly); a bare FK pair alone can't prevent a cross-tenant link.
CREATE TABLE catalog.curriculum_requirement_courses (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID NOT NULL REFERENCES institution.colleges (id),
    requirement_id      UUID NOT NULL REFERENCES catalog.curriculum_requirements (id),
    course_id           UUID NOT NULL REFERENCES catalog.courses (id),
    requirement_role    TEXT NOT NULL CHECK (requirement_role IN ('CORE','ELECTIVE','SUBSTITUTE')),
    CONSTRAINT uq_curriculum_requirement_courses UNIQUE (requirement_id, course_id)
);
