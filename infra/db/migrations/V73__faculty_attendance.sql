-- Faculty (staff) own daily attendance — see docs/erd/03-learning-assessment-records.md (`faculty_attendance`)
-- and docs/state-machines.md #25. Separate from attendance.attendance_records, which records STUDENTS in a class meeting.
CREATE TABLE attendance.faculty_attendance (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         UUID NOT NULL REFERENCES institution.colleges (id),
    faculty_id        UUID NOT NULL REFERENCES directory.faculty_profiles (id),
    -- The calendar day in the COLLEGE's timezone (institution.colleges.timezone), not UTC.
    attendance_date   DATE NOT NULL,
    status            TEXT NOT NULL CHECK (status IN ('PRESENT','HALF_DAY','ABSENT','ON_LEAVE')),
    check_in_at       TIMESTAMPTZ NULL,
    check_out_at      TIMESTAMPTZ NULL,
    -- Who last set the status: the faculty member themself (check-in) or an administrator (mark/correction).
    source            TEXT NOT NULL CHECK (source IN ('SELF','ADMIN')),
    corrected_by      UUID NULL REFERENCES identity.users (id),
    correction_reason TEXT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    version           INT NOT NULL DEFAULT 0,
    -- One record per faculty member per day, DB-enforced so two concurrent check-ins cannot both insert.
    CONSTRAINT uq_faculty_attendance_faculty_date UNIQUE (faculty_id, attendance_date),
    CONSTRAINT chk_faculty_attendance_out_needs_in CHECK (check_out_at IS NULL OR check_in_at IS NOT NULL),
    CONSTRAINT chk_faculty_attendance_times CHECK (check_out_at IS NULL OR check_out_at >= check_in_at),
    CONSTRAINT chk_faculty_attendance_absent_no_times CHECK (
        status IN ('PRESENT','HALF_DAY') OR (check_in_at IS NULL AND check_out_at IS NULL)),
    CONSTRAINT chk_faculty_attendance_admin_audited CHECK (
        source <> 'ADMIN' OR (corrected_by IS NOT NULL AND correction_reason IS NOT NULL AND length(btrim(correction_reason)) > 0))
);
CREATE INDEX idx_faculty_attendance_tenant_date ON attendance.faculty_attendance (tenant_id, attendance_date);
CREATE INDEX idx_faculty_attendance_faculty_date ON attendance.faculty_attendance (faculty_id, attendance_date DESC);

INSERT INTO identity.permissions (code, resource, action) VALUES
    ('CHECK_IN_STAFF_ATTENDANCE', 'staff_attendance', 'CHECK_IN_STAFF_ATTENDANCE'),
    ('MANAGE_STAFF_ATTENDANCE', 'staff_attendance', 'MANAGE_STAFF_ATTENDANCE')
ON CONFLICT (code) DO NOTHING;

-- FACULTY: check in / check out / read their own history (ownership is additionally enforced in the application layer).
-- COLLEGE_ADMIN and SUPER_ADMIN: view every faculty member's record in the tenant and mark/correct with a reason.
INSERT INTO identity.role_permissions (role_id, permission_code)
SELECT r.id, p.code
FROM identity.roles r
JOIN (VALUES
    ('FACULTY', 'CHECK_IN_STAFF_ATTENDANCE'),
    ('COLLEGE_ADMIN', 'MANAGE_STAFF_ATTENDANCE'),
    ('SUPER_ADMIN', 'MANAGE_STAFF_ATTENDANCE')
) AS p(role_code, code) ON p.role_code = r.code
ON CONFLICT (role_id, permission_code) DO NOTHING;
