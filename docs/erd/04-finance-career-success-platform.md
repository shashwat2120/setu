# ERD — Finance, Career, Success, Communication, Governance

Part of [../ERD.md](../ERD.md) — global conventions apply unless stated otherwise.

## Module: finance

| Table | PK | FK | Key columns | Constraints / notes |
|---|---|---|---|---|
| `fee_structures` | `id` | `tenant_id`, `program_id → programs NULL` | `name, academic_year_id → academic_years, status` | Defines what's charged, not the charge itself |
| `fee_components` | `id` | `fee_structure_id → fee_structures NOT NULL` | `code, amount NUMERIC(12,2), currency CHAR(3)` | `(fee_structure_id, code)` unique |
| `student_charges` | `id` | `tenant_id`, `student_id → student_profiles NOT NULL`, `fee_component_id → fee_components NOT NULL` | `amount, currency, due_date, status` | `status IN ('PENDING','PAID','WAIVED','OVERDUE')` |
| `student_accounts` | `id` | `tenant_id`, `student_id → student_profiles NOT NULL` | `balance NUMERIC(12,2) NOT NULL DEFAULT 0, currency` | `(tenant_id, student_id)` unique — one running-balance account per student. **`balance` is a derived/cached total, recomputable from `student_charges` + `payments`** — never the sole source of truth; reconcile, don't trust blindly |
| `payments` | `id` | `tenant_id`, `student_account_id → student_accounts NOT NULL` | `amount, currency, method, status, provider_ref TEXT` | `status IN ('INITIATED','SUCCEEDED','FAILED','REFUNDED')` |
| `payment_events` | `id` | `payment_id → payments NOT NULL` | `provider_event_id TEXT NOT NULL, event_type, payload_json, received_at` | **Unique `provider_event_id`** — this is the idempotency guard for the duplicate-webhook concurrency scenario the user explicitly flagged: the provider may retry a webhook delivery, and this unique constraint makes a second delivery of the same event a no-op insert conflict, not a double-applied payment. **The webhook is authoritative** over any client-reported payment state (canonical spec §5). No card PAN/CVV anywhere in this schema — `provider_ref` is the provider's own opaque token. |
| `receipts` | `id` | `payment_id → payments NOT NULL` | `receipt_no TEXT NOT NULL, issued_at, document_ref TEXT` | `receipt_no` unique per tenant |

Owning module: `finance`. Transaction boundary: a `payment_events` insert +
`payments.status` update + `student_accounts.balance` adjustment + outbox
`PaymentSuccessful` event all commit atomically in one transaction, keyed off
the idempotency check above so a retried webhook is a safe no-op rather than a
second transaction attempting the same balance adjustment.

## Module: career

| Table | PK | FK | Key columns | Constraints |
|---|---|---|---|---|
| `skills` | `id` | `tenant_id` (or global — see note) | `code, name, category_id → skill_categories` | `code` unique. Skills are plausibly **shared reference data across tenants** (e.g. "React", "SQL") rather than tenant-owned — flagged as a design choice, not a contradiction: default to tenant-scoped for v1 consistency with everything else, revisit if cross-tenant skill taxonomy sharing becomes a real requirement. |
| `skill_categories` | `id` | `tenant_id` | `code, name` | `(tenant_id, code)` unique |
| `student_skills` | `id` | `tenant_id`, `student_id → student_profiles NOT NULL`, `skill_id → skills NOT NULL` | `proficiency_level TEXT, evidence_ref TEXT NULL` | `(student_id, skill_id)` unique — `tenant_id` added per erd-audit-2.md finding A |
| `jobs` | `id` | `tenant_id`, `posted_by → users NOT NULL` | `title, employer TEXT, description, status` | |
| `job_skills` | `id` | `tenant_id`, `job_id → jobs NOT NULL`, `skill_id → skills NOT NULL` | `required_level TEXT` | `(job_id, skill_id)` unique — `tenant_id` added per erd-audit-2.md finding A |
| `applications` | `id` | `job_id → jobs NOT NULL`, `student_id → student_profiles NOT NULL` | `status, applied_at, notes` | `(job_id, student_id)` unique |
| `placement_records` | `id` | `student_id → student_profiles NOT NULL`, `job_id → jobs NULL` | `employer TEXT, role TEXT, ctc NUMERIC(12,2) NULL, placed_at DATE` | |

Owning module: `career`. Gap analysis (student skills vs. job requirements) is
**derived at query time**, not a stored/maintained table.

## Module: risk (Student Success Engine)

### `risk_scores`

- **Purpose**: the authoritative, reproducible output of the deterministic risk
  engine — never written or altered by the AI layer
- **PK**: `id UUID`
- **FK**: `tenant_id NOT NULL`, `student_id → student_profiles NOT NULL`
- **Columns**: `score NUMERIC(6,3) NOT NULL`, `band TEXT NOT NULL`
  (`LOW`/`MEDIUM`/`HIGH`), `rule_version TEXT NOT NULL`, `feature_snapshot_hash
  TEXT NOT NULL`, `calculated_at TIMESTAMPTZ NOT NULL DEFAULT now()`
- **Reproducibility requirement** (canonical spec §15, §11 5E): given
  `rule_version` and `feature_snapshot_hash`, the exact score must be
  re-derivable — meaning the actual feature vector used must be retrievable,
  not just its hash. **Resolved gap**: add `feature_snapshot JSONB NOT NULL`
  storing the actual input feature values alongside the hash (the hash alone
  is a verification checksum, not a reconstruction — storing only the hash
  would make "recalculate and compare" impossible if the underlying
  attendance/grade data later changes for unrelated reasons). This wasn't
  explicit in the source material and is flagged in the contradiction audit.
- **Append-only**: never update a score in place — a recalculation inserts a
  new row; "current" is the latest `calculated_at` per student.
- **Index**: `(student_id, calculated_at DESC)` — risk score *history* (a
  student's trend over time, and "get current score" as `LIMIT 1` on this
  index) is a named query pattern in the final audit's index review (section O)
  that the original ERD pass omitted entirely — no existing index covered it.
- **Owning module**: `risk`

### `risk_factors`, `recommendations`, `interventions`, `intervention_actions`

| Table | Key columns | Notes |
|---|---|---|
| `risk_factors` | `id, risk_score_id → risk_scores NOT NULL, factor_code TEXT, contribution NUMERIC(5,3), evidence_refs JSONB NOT NULL DEFAULT '[]'` | One row per contributing factor, ranked by `contribution` — this is what the `ai-explain` module is handed, not the raw feature vector, so the AI never has to (and can't) derive its own weighting. `evidence_refs` is an array of `{table, id}` objects (a deliberate typed polymorphic reference — evidence may come from `attendance_records`, `grades`, or `learning_progress`, so no single FK column can express it); see erd-audit-2.md finding C. |
| `recommendations` | `id, risk_score_id → risk_scores NOT NULL, action_code TEXT, generated_by TEXT ('RULE'\|'AI')` | The **rule engine or the AI may propose** a recommendation, but neither can mark a `risk_score` itself as resolved — that's an `interventions` state transition, performed by a human advisor |
| `interventions` | `id, tenant_id, student_id, risk_score_id → risk_scores, recommendation_id → recommendations NULL, owner_user_id → users NOT NULL, status, due_date` | Human-owned action tracking |
| `intervention_actions` | `id, intervention_id → interventions NOT NULL, action TEXT, actor_user_id, notes, created_at` | Append-only audit trail, same pattern as `enrollment_actions` |

Owning module: `risk` for all four. **AI never mutates any of these tables
directly** — `ai-explain` reads `risk_scores`/`risk_factors` (read-only, via an
authorized application call, not a direct repository reference across module
boundaries) and may *propose* a `recommendations` row through the `risk`
module's own API, which the `risk` module validates before persisting.

## Module: notification

| Table | Key columns | Notes |
|---|---|---|
| `notifications` | `id, tenant_id, user_id, type, payload_json, created_at, read_at NULL` | |
| `notification_preferences` | `id, user_id, channel, enabled BOOLEAN` | `(user_id, channel)` unique |
| `delivery_attempts` | `id, notification_id → notifications NOT NULL, channel, status, attempted_at, error TEXT NULL` | Dedupe by `(notification_id, channel)` + a business idempotency key from the originating event, per canonical spec §8 |

Owning module: `notification`. Realtime fan-out (WebSocket) is this module's
responsibility too — it's both the async notification store and the
Kafka-consuming realtime pusher (canonical spec's "Notification/Realtime
Service" pairing in Sprint 6 confirms these two concerns extract together).

## Module: governance (cross-cutting)

| Table | Key columns | Notes |
|---|---|---|
| `audit_logs` | `id, tenant_id NULL, actor_user_id NULL, action, resource_type, resource_id, before_json NULL, after_json NULL, created_at` | `tenant_id`/`actor_user_id` nullable to allow system/platform-level audit entries (e.g. a `SUPER_ADMIN` platform action). Append-only, indefinite retention — this table is never pruned by application code. |
| `outbox_events` | `id, tenant_id NULL, aggregate_type, aggregate_id, event_type, event_version, payload_json, status ('NEW'\|'PUBLISHED'\|'FAILED'), occurred_at, correlation_id, causation_id` | Written in the **same transaction** as the business change it describes (canonical spec §8) — this table has no independent owning module; every module writes to it as part of its own transaction |
| `idempotency_keys` | `id, key TEXT NOT NULL, scope TEXT NOT NULL, created_at, expires_at` | `(key, scope)` unique. **Scope narrowed per erd-audit-2.md finding D**: this is for **API-request-level idempotency only** (a client-supplied `Idempotency-Key` header on e.g. `POST /enrollments`, guarding against a flaky-connection retry creating a duplicate). It is *not* used for payment webhook dedup (`payment_events.provider_event_id` already handles that) or Kafka consumer dedup (each consumer keeps its own processed-event table per canonical spec §8) — conflating the three would recreate the cross-module coupling point the per-consumer-table guidance exists to avoid. |
| `api_clients` — **deferred, not in Sprint 5** | — | Per erd-audit-2.md finding E: no current consumer, speculative for a future M2M integration. Removed from the Sprint 5 migration set; add it in its own migration if/when a real integration scopes its actual required shape. **Architectural — awaiting your approval before finalizing.** |

`audit_logs` and `outbox_events` don't have a single "owning module" in the
Spring Modulith sense — every module writes to both as part of its own
transactions. In Sprint 6, each extracted service keeps its own
`outbox_events` table (service-owned data, canonical spec §2) rather than
sharing one physical table across services.
