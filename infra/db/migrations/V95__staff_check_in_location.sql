-- Where a staff member checked in from, as a signal for the college administrator. Off by default: a college must switch it on, and say where its campus is.
--
-- Like the student check-in location (V92) it is information and never a rule: a check-in is never refused or changed because of where the device said it
-- was, and ONLY THE OUTCOME is stored (near, far, not shared, no campus position, with the distance and the accuracy the device claimed), never the
-- person's coordinates. The campus position below is the college's own, not a person's.

CREATE TABLE attendance.staff_location_policies (
    -- one row per college; no foreign key to the college (Attendance owns its tables and reads Institution through its read model)
    tenant_id          UUID PRIMARY KEY,
    enabled            BOOLEAN NOT NULL DEFAULT FALSE,
    campus_latitude    DOUBLE PRECISION NULL,
    campus_longitude   DOUBLE PRECISION NULL,
    -- how far from the campus position still counts as on campus (the device's own claimed accuracy is allowed for on top of this)
    radius_meters      INT NOT NULL DEFAULT 300,
    updated_by         UUID NULL,
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    version            INT NOT NULL DEFAULT 0,
    CONSTRAINT chk_staff_location_lat CHECK (campus_latitude IS NULL OR campus_latitude BETWEEN -90 AND 90),
    CONSTRAINT chk_staff_location_lon CHECK (campus_longitude IS NULL OR campus_longitude BETWEEN -180 AND 180),
    -- a campus position is both coordinates or neither (the same lesson as V93), and switching the check on needs one
    CONSTRAINT chk_staff_location_both_or_neither CHECK ((campus_latitude IS NULL) = (campus_longitude IS NULL)),
    CONSTRAINT chk_staff_location_enabled_needs_position CHECK (NOT enabled OR campus_latitude IS NOT NULL),
    CONSTRAINT chk_staff_location_radius CHECK (radius_meters BETWEEN 50 AND 5000)
);

ALTER TABLE attendance.faculty_attendance
    ADD COLUMN location_status          TEXT NULL,
    ADD COLUMN location_distance_meters INT NULL,
    ADD COLUMN location_accuracy_meters INT NULL,
    ADD CONSTRAINT chk_faculty_attendance_location_status CHECK (
        location_status IS NULL OR location_status IN ('NEAR', 'FAR', 'NO_LOCATION', 'NO_REFERENCE')),
    ADD CONSTRAINT chk_faculty_attendance_location_numbers CHECK (
        (location_distance_meters IS NULL OR location_distance_meters >= 0)
        AND (location_accuracy_meters IS NULL OR location_accuracy_meters >= 0)
        AND (location_status IS NOT NULL OR (location_distance_meters IS NULL AND location_accuracy_meters IS NULL)));
