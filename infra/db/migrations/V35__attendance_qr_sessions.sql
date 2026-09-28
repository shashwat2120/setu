CREATE TABLE attendance.attendance_qr_sessions (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    attendance_session_id   UUID NOT NULL REFERENCES attendance.attendance_sessions (id),
    nonce                   TEXT NOT NULL,
    expires_at              TIMESTAMPTZ NOT NULL,
    rotation_no             INT NOT NULL DEFAULT 0
);
CREATE INDEX idx_attendance_qr_sessions_latest ON attendance.attendance_qr_sessions (attendance_session_id, rotation_no DESC);
