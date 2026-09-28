CREATE TABLE enrollment.faculty_assignments (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES institution.colleges (id),
    section_id      UUID NOT NULL REFERENCES enrollment.course_sections (id),
    faculty_id      UUID NOT NULL REFERENCES directory.faculty_profiles (id),
    role            TEXT NOT NULL CHECK (role IN ('PRIMARY','CO_INSTRUCTOR','TA')),
    allocation_pct  NUMERIC(5,2) NULL,
    starts_at       DATE NULL,
    ends_at         DATE NULL
);
CREATE UNIQUE INDEX uq_faculty_assignments_one_active_primary ON enrollment.faculty_assignments (section_id) WHERE role = 'PRIMARY' AND ends_at IS NULL;
-- Added in the final consistency audit's index review (section O/F) — "what am I teaching".
CREATE INDEX idx_faculty_assignments_faculty_active ON enrollment.faculty_assignments (faculty_id) WHERE ends_at IS NULL;
