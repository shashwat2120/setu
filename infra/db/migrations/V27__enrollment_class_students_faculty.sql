-- tenant_id on both per docs/erd-audit-2.md finding A.
CREATE TABLE enrollment.class_students (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID NOT NULL REFERENCES institution.colleges (id),
    class_id    UUID NOT NULL REFERENCES enrollment.classes (id),
    student_id  UUID NOT NULL REFERENCES directory.student_profiles (id),
    starts_at   DATE NOT NULL,
    ends_at     DATE NULL,
    status      TEXT NOT NULL DEFAULT 'ACTIVE'
);
CREATE UNIQUE INDEX uq_class_students_active ON enrollment.class_students (class_id, student_id) WHERE status = 'ACTIVE';

CREATE TABLE enrollment.class_faculty (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID NOT NULL REFERENCES institution.colleges (id),
    class_id    UUID NOT NULL REFERENCES enrollment.classes (id),
    faculty_id  UUID NOT NULL REFERENCES directory.faculty_profiles (id),
    role        TEXT NOT NULL CHECK (role IN ('ADVISOR','COORDINATOR')),
    starts_at   DATE NULL,
    ends_at     DATE NULL
);
