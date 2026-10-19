-- Where a check-in came from, as a signal for the instructor (never a gate).
--
-- The session carries the classroom position: the instructor's device reports it when the session is opened, and it is optional.
-- A record carries only the OUTCOME of comparing the student's reported position with it: a status, the distance and the accuracy the device
-- reported. The student's own coordinates are not stored anywhere. All columns are nullable: sessions and records written before this
-- migration, or without a position, simply have none.

ALTER TABLE attendance.attendance_sessions
    ADD COLUMN anchor_latitude  DOUBLE PRECISION,
    ADD COLUMN anchor_longitude DOUBLE PRECISION,
    ADD CONSTRAINT chk_attendance_sessions_anchor CHECK (
        (anchor_latitude IS NULL AND anchor_longitude IS NULL)
        OR (anchor_latitude BETWEEN -90 AND 90 AND anchor_longitude BETWEEN -180 AND 180));

ALTER TABLE attendance.attendance_records
    ADD COLUMN location_status          TEXT,
    ADD COLUMN location_distance_meters INTEGER,
    ADD COLUMN location_accuracy_meters INTEGER,
    ADD CONSTRAINT chk_attendance_records_location_status CHECK (
        location_status IS NULL OR location_status IN ('NEAR', 'FAR', 'NO_LOCATION', 'NO_REFERENCE')),
    ADD CONSTRAINT chk_attendance_records_location_numbers CHECK (
        (location_distance_meters IS NULL OR location_distance_meters >= 0)
        AND (location_accuracy_meters IS NULL OR location_accuracy_meters >= 0));

COMMENT ON COLUMN attendance.attendance_records.location_status IS
    'NEAR / FAR: compared with the classroom position of the session; NO_LOCATION: the student did not or could not share a position; NO_REFERENCE: the session has no classroom position to compare with. Informational: it never changes whether the check-in counts.';
