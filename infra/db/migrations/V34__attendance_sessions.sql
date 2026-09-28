CREATE TABLE attendance.attendance_sessions (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID NOT NULL REFERENCES institution.colleges (id),
    section_id  UUID NOT NULL REFERENCES enrollment.course_sections (id),
    meeting_id  UUID NOT NULL REFERENCES catalog.meeting_patterns (id),
    opened_by   UUID NOT NULL REFERENCES identity.users (id),
    started_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    ends_at     TIMESTAMPTZ NOT NULL,
    -- Hash of the CURRENT rotating token only (attendance_qr_sessions) — the
    -- raw token is never persisted anywhere (docs/contradiction-audit.md #9).
    token_hash  TEXT NOT NULL,
    status      TEXT NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','CLOSED')),
    version     INT NOT NULL DEFAULT 0,
    CONSTRAINT chk_attendance_sessions_times CHECK (ends_at > started_at)
);
-- One active session per meeting.
CREATE UNIQUE INDEX uq_attendance_sessions_one_open_per_meeting ON attendance.attendance_sessions (meeting_id) WHERE status = 'OPEN';
