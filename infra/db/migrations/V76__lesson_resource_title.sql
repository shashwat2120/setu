-- A human-readable name for an attached resource (for example "Think Python, 2nd edition (PDF)"). Optional: existing rows keep working
-- and show their address instead. The resource itself is still only a REFERENCE (media_ref); binary files never live in PostgreSQL.
ALTER TABLE learning.lesson_resources
    ADD COLUMN title TEXT NULL CHECK (title IS NULL OR length(btrim(title)) BETWEEN 1 AND 200);
