CREATE TABLE enrollment.course_sections (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id               UUID NOT NULL REFERENCES institution.colleges (id),
    course_id               UUID NOT NULL REFERENCES catalog.courses (id),
    period_id               UUID NOT NULL REFERENCES catalog.academic_periods (id),
    -- Nullable per docs/contradiction-audit.md #7 (APPROVED): a section MAY be
    -- cohort-linked (mandatory offering) or NULL (open elective).
    class_id                UUID NULL REFERENCES enrollment.classes (id),
    section_code            TEXT NOT NULL,
    capacity                INT NOT NULL CHECK (capacity > 0),
    room_id                 UUID NULL REFERENCES catalog.rooms (id),
    status                  TEXT NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT','OPEN','CLOSED','CANCELLED')),
    registration_open_at    TIMESTAMPTZ NULL,
    registration_close_at   TIMESTAMPTZ NULL,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by              UUID NOT NULL REFERENCES identity.users (id),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by              UUID NOT NULL REFERENCES identity.users (id),
    version                 INT NOT NULL DEFAULT 0,
    CONSTRAINT uq_course_sections_tenant_course_period_code UNIQUE (tenant_id, course_id, period_id, section_code)
);
CREATE INDEX idx_course_sections_period ON enrollment.course_sections (period_id);
