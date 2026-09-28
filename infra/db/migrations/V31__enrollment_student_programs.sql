CREATE TABLE enrollment.student_programs (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES institution.colleges (id),
    student_id      UUID NOT NULL REFERENCES directory.student_profiles (id),
    program_id      UUID NOT NULL REFERENCES catalog.programs (id),
    curriculum_id   UUID NOT NULL REFERENCES catalog.curricula (id),
    admission_year  INT NOT NULL,
    status          TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','ON_HOLD','COMPLETED','DISCONTINUED')),
    is_primary      BOOLEAN NOT NULL DEFAULT true,
    starts_at       DATE NOT NULL,
    ends_at         DATE NULL
);
-- Default: one active PRIMARY program per student (approved decision H3...
-- wait, this is H8 not H3). Approved decision from contradiction-audit #8:
-- single active primary program by default; colleges.settings_json ->>
-- 'allow_dual_program' permits additional non-primary ACTIVE rows, which
-- this partial index does not constrain.
CREATE UNIQUE INDEX uq_student_programs_one_active_primary ON enrollment.student_programs (tenant_id, student_id) WHERE status = 'ACTIVE' AND is_primary = true;
