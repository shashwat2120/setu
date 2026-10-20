-- A student's resume, and the resume an application was made with.
--
-- student_resumes holds one resume per student as a JSON document (headline, summary, links, projects, certifications, experience), validated by the service, with an
-- optimistic-locking version. student_id is Directory's, kept without a foreign key so Career stays free of another module's table (the service checks it at write time).
--
-- An application keeps a snapshot of the resume it was made with, together with the student's record at that moment (CGPA, attendance, batch, skills), so the placement
-- officer reviews what was submitted and not whatever the student has edited since. NULL when the student had no resume when they applied.
--
-- A job can say it needs a resume; applying then fails with the reason, like any other rule.
CREATE TABLE career.student_resumes (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID NOT NULL REFERENCES institution.colleges (id),
    student_id  UUID NOT NULL,
    content     JSONB NOT NULL DEFAULT '{}',
    version     INT NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_student_resumes_student UNIQUE (student_id)
);
CREATE INDEX idx_student_resumes_tenant ON career.student_resumes (tenant_id);

ALTER TABLE career.applications
    ADD COLUMN resume_snapshot JSONB NULL,
    ADD COLUMN resume_version  INT NULL;

ALTER TABLE career.jobs
    ADD COLUMN requires_resume BOOLEAN NOT NULL DEFAULT false;
