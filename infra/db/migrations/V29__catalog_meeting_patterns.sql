-- In `catalog` schema conceptually, but migrates after enrollment.course_sections
-- (V28) because of the FK — cross-schema ordering follows dependency, not
-- schema grouping (docs/migration-order.md).
CREATE TABLE catalog.meeting_patterns (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES institution.colleges (id),
    section_id      UUID NOT NULL REFERENCES enrollment.course_sections (id),
    day_of_week     SMALLINT NOT NULL CHECK (day_of_week BETWEEN 0 AND 6),
    start_time      TIME NOT NULL,
    end_time        TIME NOT NULL,
    room_id         UUID NOT NULL REFERENCES catalog.rooms (id),
    effective_from  DATE NULL,
    effective_to    DATE NULL,
    -- Postgres has no native TIME range type; derive a tsrange (arbitrary
    -- fixed date) purely so the EXCLUDE constraint below can use && overlap.
    time_range      TSRANGE GENERATED ALWAYS AS (
                        tsrange(('2000-01-01'::date + start_time), ('2000-01-01'::date + end_time))
                    ) STORED,
    CONSTRAINT chk_meeting_patterns_times CHECK (end_time > start_time),
    -- DB-enforced room-overlap prevention (the EXCLUDE constraint flagged as
    -- required in docs/erd/02-academic.md), needs btree_gist (V3) for the
    -- equality operators inside a GiST index.
    CONSTRAINT excl_meeting_patterns_room_overlap EXCLUDE USING gist (
        room_id WITH =,
        day_of_week WITH =,
        time_range WITH &&
    )
);
CREATE INDEX idx_meeting_patterns_section ON catalog.meeting_patterns (section_id);

-- NOTE: faculty-level overlap (the same instructor double-booked across two
-- different sections at once) is NOT enforced by a DB constraint here —
-- faculty is not a column on this table (it's reached via course_sections ->
-- enrollment.faculty_assignments), so a single EXCLUDE constraint can't
-- express it directly. That check remains an application-level join at
-- section-scheduling time. Flagged honestly rather than claiming DB-level
-- enforcement this table's shape can't actually deliver.
