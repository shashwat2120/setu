-- Faculty attendance regularisation — see docs/erd/03-learning-assessment-records.md
-- (`faculty_attendance_regularisations`) and docs/state-machines.md #26.
CREATE TABLE attendance.faculty_attendance_regularisations (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id               UUID NOT NULL REFERENCES institution.colleges (id),
    faculty_id              UUID NOT NULL REFERENCES directory.faculty_profiles (id),
    -- The calendar day in the COLLEGE's timezone; the service only accepts days strictly before today.
    attendance_date         DATE NOT NULL,
    requested_status        TEXT NOT NULL CHECK (requested_status IN ('PRESENT','HALF_DAY','ON_LEAVE')),
    requested_check_in_at   TIMESTAMPTZ NULL,
    requested_check_out_at  TIMESTAMPTZ NULL,
    reason                  TEXT NOT NULL CHECK (length(btrim(reason)) > 0),
    status                  TEXT NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','APPROVED','REJECTED','WITHDRAWN')),
    decided_by              UUID NULL REFERENCES identity.users (id),
    decided_at              TIMESTAMPTZ NULL,
    decision_note           TEXT NULL,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    version                 INT NOT NULL DEFAULT 0,
    CONSTRAINT chk_regularisation_times_only_for_work_days CHECK (
        requested_status IN ('PRESENT','HALF_DAY') OR (requested_check_in_at IS NULL AND requested_check_out_at IS NULL)),
    CONSTRAINT chk_regularisation_out_needs_in CHECK (requested_check_out_at IS NULL OR requested_check_in_at IS NOT NULL),
    CONSTRAINT chk_regularisation_out_after_in CHECK (requested_check_out_at IS NULL OR requested_check_out_at > requested_check_in_at),
    -- decided_by / decided_at are set exactly when a request has been approved or rejected.
    CONSTRAINT chk_regularisation_decision_fields CHECK (
        (status IN ('APPROVED','REJECTED') AND decided_by IS NOT NULL AND decided_at IS NOT NULL)
        OR (status IN ('PENDING','WITHDRAWN') AND decided_by IS NULL AND decided_at IS NULL)),
    CONSTRAINT chk_regularisation_rejection_note CHECK (
        status <> 'REJECTED' OR (decision_note IS NOT NULL AND length(btrim(decision_note)) > 0))
);
-- At most one open request per faculty member per day, DB-enforced.
CREATE UNIQUE INDEX uq_faculty_regularisation_one_pending_per_day
    ON attendance.faculty_attendance_regularisations (faculty_id, attendance_date) WHERE status = 'PENDING';
CREATE INDEX idx_faculty_regularisation_tenant_status ON attendance.faculty_attendance_regularisations (tenant_id, status, created_at);
CREATE INDEX idx_faculty_regularisation_faculty ON attendance.faculty_attendance_regularisations (faculty_id, created_at DESC);
