-- Staff (faculty) attendance policy and policy-derived metrics — see docs/erd/03-learning-assessment-records.md
-- (`staff_attendance_policies`, `faculty_attendance` policy-derived metrics) and docs/state-machines.md #25.

-- Append-only versions: changing the rules inserts a new row so each attendance record can cite the exact version it was judged under.
CREATE TABLE attendance.staff_attendance_policies (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         UUID NOT NULL REFERENCES institution.colleges (id),
    version           INT NOT NULL CHECK (version >= 1),
    -- College-local wall-clock times, stored as MINUTES SINCE MIDNIGHT (0-1439) rather than TIME: Hibernate's global jdbc.time_zone=UTC would
    -- otherwise shift a TIME by the JVM's own offset on a non-UTC machine, storing the wrong clock time. The college's timezone applies when used.
    shift_start_minute INT NOT NULL CHECK (shift_start_minute BETWEEN 0 AND 1439),
    shift_end_minute   INT NOT NULL CHECK (shift_end_minute BETWEEN 0 AND 1439),
    grace_minutes     INT NOT NULL,
    full_day_minutes  INT NOT NULL,
    half_day_minutes  INT NOT NULL,
    created_by        UUID NOT NULL REFERENCES identity.users (id),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Two administrators saving at once cannot both take the same version.
    CONSTRAINT uq_staff_attendance_policies_tenant_version UNIQUE (tenant_id, version),
    CONSTRAINT chk_staff_policy_shift CHECK (shift_start_minute < shift_end_minute),
    CONSTRAINT chk_staff_policy_grace CHECK (grace_minutes BETWEEN 0 AND 120),
    CONSTRAINT chk_staff_policy_hours CHECK (half_day_minutes > 0 AND half_day_minutes < full_day_minutes AND full_day_minutes <= 1440)
);
CREATE INDEX idx_staff_attendance_policies_tenant_version ON attendance.staff_attendance_policies (tenant_id, version DESC);

-- Metrics computed under the policy in force. NULL on rows evaluated before this migration (no policy existed) and on days with no work times.
ALTER TABLE attendance.faculty_attendance
    ADD COLUMN late_minutes   INT NULL CHECK (late_minutes IS NULL OR late_minutes > 0),
    ADD COLUMN worked_minutes INT NULL CHECK (worked_minutes IS NULL OR worked_minutes >= 0),
    ADD COLUMN policy_version INT NULL CHECK (policy_version IS NULL OR policy_version >= 0),
    ADD COLUMN short_hours    BOOLEAN NOT NULL DEFAULT FALSE;

-- ABSENT / ON_LEAVE carry no work times, so they carry no metrics either.
ALTER TABLE attendance.faculty_attendance
    ADD CONSTRAINT chk_faculty_attendance_no_metrics_without_work CHECK (
        status IN ('PRESENT','HALF_DAY')
        OR (late_minutes IS NULL AND worked_minutes IS NULL AND policy_version IS NULL AND short_hours = FALSE));
