-- A placement officer's decision on an application (shortlist, reject, select) can carry a reason the student is shown; a rejection must carry one
-- (enforced in the service, and here too so no writer can skip it). decided_at is when the last such decision was made.
ALTER TABLE career.applications
    ADD COLUMN decision_reason TEXT NULL,
    ADD COLUMN decided_at      TIMESTAMPTZ NULL,
    ADD CONSTRAINT ck_applications_decision_reason CHECK (decision_reason IS NULL OR (btrim(decision_reason) <> '' AND char_length(decision_reason) <= 500)),
    ADD CONSTRAINT ck_applications_rejection_has_reason CHECK (status <> 'REJECTED' OR decision_reason IS NOT NULL OR decided_at IS NULL);
