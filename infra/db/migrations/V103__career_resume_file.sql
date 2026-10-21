-- A student's uploaded resume file (a PDF). The file itself is kept on a storage volume under the SHA-256 of its content, never in the database; the row keeps only the
-- reference (the hash), the display name the student gave it, and its size. Content-addressed files never change, so an application that was made with this file keeps
-- pointing at exactly it (the application's resume snapshot carries the same reference) even after the student replaces or removes theirs.
ALTER TABLE career.student_resumes
    ADD COLUMN file_sha256      TEXT NULL,
    ADD COLUMN file_name        TEXT NULL,
    ADD COLUMN file_size        INT NULL,
    ADD COLUMN file_uploaded_at TIMESTAMPTZ NULL,
    ADD CONSTRAINT ck_student_resumes_file_all_or_none CHECK (
        (file_sha256 IS NULL AND file_name IS NULL AND file_size IS NULL AND file_uploaded_at IS NULL)
        OR (file_sha256 ~ '^[0-9a-f]{64}$' AND file_name IS NOT NULL AND file_size > 0 AND file_uploaded_at IS NOT NULL)),
    ADD CONSTRAINT ck_student_resumes_file_size CHECK (file_size IS NULL OR file_size <= 2097152);
