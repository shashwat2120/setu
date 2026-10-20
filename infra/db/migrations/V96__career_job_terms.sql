-- What a placement officer can say about a job beyond its title: who may apply (academic cut-offs, programs, a deadline) and what it is (package, place, openings, type).
-- Every field is optional: a job with none behaves exactly as before (open to any student whose skills meet its requirements).
--
-- min_cgpa is on the college's own grade-point scale (the demo uses 4.0), min_attendance_pct is 0 to 100. allowed_program_ids lists the programs whose students may
-- apply (null or empty: any program); the ids are Catalog's, kept without a foreign key so Career stays free of another module's table.
ALTER TABLE career.jobs
    ADD COLUMN min_cgpa              NUMERIC(4, 2) NULL,
    ADD COLUMN min_attendance_pct    NUMERIC(5, 2) NULL,
    ADD COLUMN application_deadline  TIMESTAMPTZ   NULL,
    ADD COLUMN package_lpa           NUMERIC(8, 2) NULL,
    ADD COLUMN location              TEXT          NULL,
    ADD COLUMN openings              INT           NULL,
    ADD COLUMN job_type              TEXT          NULL,
    ADD COLUMN allowed_program_ids   UUID[]        NULL,
    ADD CONSTRAINT chk_jobs_min_cgpa CHECK (min_cgpa IS NULL OR (min_cgpa >= 0 AND min_cgpa <= 10)),
    ADD CONSTRAINT chk_jobs_min_attendance CHECK (min_attendance_pct IS NULL OR (min_attendance_pct >= 0 AND min_attendance_pct <= 100)),
    ADD CONSTRAINT chk_jobs_package CHECK (package_lpa IS NULL OR package_lpa >= 0),
    ADD CONSTRAINT chk_jobs_openings CHECK (openings IS NULL OR openings >= 1),
    ADD CONSTRAINT chk_jobs_job_type CHECK (job_type IS NULL OR job_type IN ('FULL_TIME', 'INTERNSHIP', 'CONTRACT'));
