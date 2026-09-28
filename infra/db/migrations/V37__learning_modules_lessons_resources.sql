CREATE TABLE learning.course_modules (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID NOT NULL REFERENCES institution.colleges (id),
    section_id  UUID NOT NULL REFERENCES enrollment.course_sections (id),
    title       TEXT NOT NULL,
    sequence    INT NOT NULL,
    status      TEXT NOT NULL DEFAULT 'ACTIVE',
    CONSTRAINT uq_course_modules_section_sequence UNIQUE (section_id, sequence)
);

CREATE TABLE learning.lessons (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    module_id           UUID NOT NULL REFERENCES learning.course_modules (id),
    title               TEXT NOT NULL,
    type                TEXT NULL,
    content_ref         TEXT NULL,
    sequence            INT NOT NULL,
    duration_minutes    INT NULL,
    CONSTRAINT uq_lessons_module_sequence UNIQUE (module_id, sequence)
);

-- Binary media stays OUT of Postgres — media_ref is an object-storage
-- key/URL, never bytes (canonical spec §15).
CREATE TABLE learning.lesson_resources (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    lesson_id   UUID NOT NULL REFERENCES learning.lessons (id),
    media_ref   TEXT NOT NULL,
    type        TEXT NOT NULL
);
