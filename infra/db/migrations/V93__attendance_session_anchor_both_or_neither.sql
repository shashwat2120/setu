-- V92's check let a half position through: with a NULL longitude, "longitude BETWEEN -180 AND 180" is NULL, and a CHECK treats NULL as passing.
-- A classroom position is both coordinates or neither. Caught by AttendanceCheckInLocationTests.

-- a half position that the old check let in is no position (none exist in real data: the application only ever writes both)
UPDATE attendance.attendance_sessions
   SET anchor_latitude = NULL, anchor_longitude = NULL
 WHERE (anchor_latitude IS NULL) <> (anchor_longitude IS NULL);

ALTER TABLE attendance.attendance_sessions DROP CONSTRAINT chk_attendance_sessions_anchor;

ALTER TABLE attendance.attendance_sessions
    ADD CONSTRAINT chk_attendance_sessions_anchor CHECK (
        (anchor_latitude IS NULL AND anchor_longitude IS NULL)
        OR (anchor_latitude IS NOT NULL AND anchor_longitude IS NOT NULL
            AND anchor_latitude BETWEEN -90 AND 90 AND anchor_longitude BETWEEN -180 AND 180));
