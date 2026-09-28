-- Day 12 §Finance idempotent payment handling: governance.idempotency_keys
-- (V4) already exists as "API-request-level idempotency ... a
-- client-supplied Idempotency-Key header" with a UNIQUE (key, scope)
-- constraint, but V4 never needed to store WHAT was done under a key or
-- WHETHER a retry's payload actually matches the original request — it was
-- never actually consumed by any Java code until now. Extending the
-- existing table (additive, backward-compatible) rather than inventing a
-- parallel finance-specific idempotency table, per the "reuse existing
-- infrastructure" instruction.
--
-- request_fingerprint: a deterministic hash of the request payload, so a
-- retried request under the SAME key can be told apart from a genuinely
-- different request that accidentally reused the key — the latter is a
-- conflict, never silently processed as a retry.
--
-- status/result_json: PENDING means the key is claimed but the underlying
-- operation hasn't finished yet (a genuinely concurrent duplicate request
-- must not replay a result that doesn't exist yet); COMPLETED means
-- result_json holds the exact response a matching-fingerprint retry must
-- replay verbatim, never re-executing the financial effect.
ALTER TABLE governance.idempotency_keys
    ADD COLUMN request_fingerprint TEXT NULL,
    ADD COLUMN status TEXT NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'COMPLETED')),
    ADD COLUMN result_json JSONB NULL;
