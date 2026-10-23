-- The Attendance service's first migration of its own. A section's sessions are listed newest first (AttendanceSessionRepository.findBySectionIdOrderByStartedAtDesc),
-- and nothing indexed section_id: every faculty and student view of a section's attendance read the whole table.
CREATE INDEX IF NOT EXISTS idx_attendance_sessions_section_started ON attendance.attendance_sessions (section_id, started_at DESC);
