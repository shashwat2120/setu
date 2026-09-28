CREATE TABLE attendance.attendance_records (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    attendance_session_id   UUID NOT NULL REFERENCES attendance.attendance_sessions (id),
    enrollment_id           UUID NOT NULL REFERENCES enrollment.enrollments (id),
    corrected_by            UUID NULL REFERENCES identity.users (id),
    status                  TEXT NOT NULL CHECK (status IN ('PRESENT','LATE','ABSENT','EXCUSED')),
    marked_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    source                  TEXT NOT NULL CHECK (source IN ('QR_SCAN','MANUAL','IMPORT')),
    -- Added per transaction-concurrency-matrix.md scenario 4 — this row is
    -- updatable (a narrow, audited exception to append-only), so it needs
    -- optimistic locking like any other mutable row.
    version                 INT NOT NULL DEFAULT 0,
    -- Duplicate-scan concurrency guard, DB-enforced (docs/erd/03-....md).
    CONSTRAINT uq_attendance_records_session_enrollment UNIQUE (attendance_session_id, enrollment_id)
);
-- The attendance-percentage query: PRESENT/LATE count vs total, per enrollment.
CREATE INDEX idx_attendance_records_enrollment_status ON attendance.attendance_records (enrollment_id, status);
