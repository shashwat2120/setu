-- The date a placed student is to join the employer (placed_at stays the date the placement was made). NULL when it is not known yet.
ALTER TABLE career.placement_records ADD COLUMN joining_date DATE NULL;
