-- A job can be open to one graduating batch only (for example "the batch of 2027"): the year a student is expected to graduate, worked out from their own record
-- (admission year plus the length of their program). NULL means any batch.
ALTER TABLE career.jobs
    ADD COLUMN graduating_year INT NULL,
    ADD CONSTRAINT ck_jobs_graduating_year CHECK (graduating_year IS NULL OR graduating_year BETWEEN 2000 AND 2100);
