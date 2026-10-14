# Setu — OpenAPI Contract Catalog

Pre-coding design artifact. Not a full OpenAPI spec — a catalog establishing
every endpoint's contract shape before any controller is written. All
endpoints share a common envelope, stated once below rather than repeated
per row.

## Shared conventions

- **Base path**: `/api/v1/{module}/...` — Sprint 5 monolith serves all
  modules from one app; Sprint 6's Gateway routes the same paths to the
  extracted service.
- **Auth**: `Authorization: Bearer <access_token>` on every endpoint except
  `POST /api/v1/identity/auth/*` (login/refresh itself). Access token carries
  the resolved `tenant_id` claim (per [contradiction audit #5](contradiction-audit.md#5-session--access-token-tenant-scope)).
- **Error envelope**: `{ "error": { "code": "ENROLLMENT_CAPACITY_FULL",
  "message": "...", "correlationId": "...", "details": {...} } }` — a stable
  `code` for programmatic handling, `message` for humans, never a raw
  exception/stack trace. HTTP status follows the error class (400 validation,
  401 unauthenticated, 403 unauthorized, 404 not found *within scope* — a
  cross-tenant object ID returns 404, never 403, so as not to confirm the ID
  exists in another tenant, closing an enumeration side-channel), 409
  conflict (state machine violation, concurrency), 422 domain-invariant
  violation, 429 rate-limited, 5xx server.
- **Pagination**: cursor-based (`?cursor=...&limit=50`, response includes
  `nextCursor`), not offset — offset pagination on a frequently-inserted
  table (e.g. `enrollments`) produces skipped/duplicated rows across pages,
  a class of bug worth avoiding by construction.
- **Idempotency**: any `POST` that creates a resource with real-world side
  effects (enrollment, payment initiation, attendance scan) accepts an
  optional `Idempotency-Key` header, checked against `governance.idempotency_keys`.
- **Concurrency**: any `PATCH`/`PUT` on a versioned resource accepts an
  `If-Match: "<version>"` header (optimistic concurrency); a mismatch returns
  `409` with `code: VERSION_CONFLICT`.

## Identity module (`/api/v1/identity`)

| Method & path | Auth | Permission | Tenant scope | Notes |
|---|---|---|---|---|
| `POST /auth/passkey/register/options` | Bearer (mid-registration) | self | none (pre-tenant) | Returns WebAuthn challenge; challenge bound to the registration attempt server-side (canonical spec §6) |
| `POST /auth/passkey/register/verify` | Bearer | self | none | Verifies RP ID/origin/challenge/credential; idempotent on `credential_id` unique constraint |
| `POST /auth/passkey/login/options` | none | — | none | Public — returns a challenge for an email/username |
| `POST /auth/passkey/login/verify` | none | — | resolves tenant via `user_roles` | Issues access + refresh token pair. **Concurrency**: none needed, stateless verification |
| `POST /auth/password/login` | none | — | resolves tenant | Rate-limited per IP + per account (Redis); Argon2id verify |
| `POST /auth/token/refresh` | Refresh cookie | self | carries forward | **Concurrency-critical**: see transaction-concurrency-matrix.md "refresh-token reuse" |
| `POST /auth/logout` | Bearer | self | — | Revokes the current session |
| `GET /me/roles` | Bearer | self | all tenants the user has roles in | Drives tenant-switcher UI for cross-tenant users |
| `POST /me/tenant-context` | Bearer | self | switches active tenant claim | Re-issues an access token scoped to the chosen tenant, without a full re-login |

## Institution/Academic module (`/api/v1/institution`, `/catalog`, `/enrollment`)

| Method & path | Permission | Tenant scope | Idempotency/Concurrency |
|---|---|---|---|
| `POST /institution/departments` | `COLLEGE_ADMIN` | tenant | standard create |
| `GET /catalog/courses?department=...` | any tenant member | tenant | cursor pagination |
| `POST /catalog/courses/{id}/prerequisites` | `COLLEGE_ADMIN` | tenant | **cycle-check required before insert** (ERD note) — `422 PREREQUISITE_CYCLE` if the graph walk fails |
| `POST /catalog/sections` | `COLLEGE_ADMIN` | tenant | — |
| `POST /enrollment/enrollments` | `STUDENT` (self) or `COLLEGE_ADMIN` | tenant + resource (own record, or admin) | **The** concurrency-critical endpoint — `Idempotency-Key` required (not just optional) given the retry-safety requirement in TRD §8; see transaction-concurrency-matrix.md "last-seat enrollment race" |
| `DELETE /enrollment/enrollments/{id}` (drop) | `STUDENT` (self, within window) or `COLLEGE_ADMIN` | resource ownership | `If-Match` version header |
| `POST /enrollment/enrollments/{id}/overrides` | `COLLEGE_ADMIN` | tenant | Creates an `enrollment_overrides` row, referenced by the next enrollment attempt |
| `GET /enrollment/sections/{id}/waitlist` | `FACULTY` (assigned) / `COLLEGE_ADMIN` | resource assignment | — |
| `POST /institution/guardian-access/grants` | `COLLEGE_ADMIN` or `STUDENT` (self-service, per authorization-matrix.md) | resource ownership | Never the guardian themself (explicit denial, authorization-matrix.md) |
| `DELETE /institution/guardian-access/grants/{id}` (revoke) | same as above | resource ownership | See transaction-concurrency-matrix.md "guardian grant revoke/access race" |

## Attendance module (`/api/v1/attendance`)

| Method & path | Permission | Notes |
|---|---|---|
| `POST /sessions` (open) | `FACULTY` (assigned to section) | Enforces "one OPEN session per meeting" (state machine) |
| `GET /sessions/{id}/qr` | `FACULTY` (owner) | Returns current rotating token (never the raw stored form — there is none, per ERD) |
| `POST /sessions/{id}/scan` | `STUDENT` (self, enrolled) | **The** concurrency-critical endpoint — see transaction-concurrency-matrix.md "duplicate QR scan." Validates token/tenant/session/expiry/enrollment/duplicate in one transaction. |
| `PATCH /records/{id}` (correction) | `FACULTY` (owner) / `COLLEGE_ADMIN` | Requires `reason` in body; see "concurrent attendance correction" in the matrix |
| `POST /sessions/{id}/close` | `FACULTY` (owner) or automatic | — |

## Learning module (`/api/v1/learning`)

Standard CRUD for `modules`/`lessons`/`resources` (`FACULTY` write, enrolled
`STUDENT` read), `learning_progress` (`STUDENT` self-write),
`assignments`/`assignment_submissions` (`FACULTY` grades, `STUDENT` submits —
`POST /submissions` idempotent on `(assignment_id, student_id, attempt_no)`).

## Assessment module (`/api/v1/assessment`)

| Method & path | Permission | Notes |
|---|---|---|
| `POST /assessments` | `FACULTY` (assigned section) | Creates `DRAFT` |
| `POST /assessments/{id}/publish` | `FACULTY` or `COLLEGE_ADMIN` approval | State transition `DRAFT→SCHEDULED`; validates component weight sum (422 if invalid) |
| `POST /assessments/{id}/attempts` | `STUDENT` (enrolled) | Blocked if a second `IN_PROGRESS` attempt exists (unless multi-attempt configured) |
| `POST /attempts/{id}/submit` | `STUDENT` (self) | Idempotent — a duplicate submit of an already-`SUBMITTED` attempt is a no-op, not an error |
| `POST /grades` (publish) | `FACULTY` (owner) | **Concurrency-critical** — see "concurrent grade update" in the matrix. Always inserts a new row (append-only), never `PATCH`es a grade in place |

## Academic Record module (`/api/v1/record`)

| Method & path | Permission | Notes |
|---|---|---|
| `GET /students/{id}/record` | self / `COLLEGE_ADMIN` / `FACULTY` (advisee) / `GUARDIAN` (if granted) | Reads posted `student_academic_periods` + `student_academic_records` |
| `POST /students/{id}/transcripts` (generate) | self-request or `COLLEGE_ADMIN` | Creates a new versioned `transcripts` row, async job pattern (see Kafka catalog `TranscriptRequested`/`TranscriptGenerated`) |
| `POST /graduation-applications` | `STUDENT` (self) | Consumes Catalog's eligibility check via application call at submit AND at review |
| `POST /graduation-applications/{id}/review` | `COLLEGE_ADMIN` | Re-checks eligibility live (not from submission-time cache) |

## Finance, Career, Student Success, Notification, AI

Given the volume already covered in the ERD/state machines, these follow the
same shape as above (permission = role + ownership/assignment, tenant scope =
always tenant-filtered, idempotency on every money-moving or state-creating
POST). The two worth calling out specifically:

| Method & path | Notes |
|---|---|
| `POST /finance/payments/{paymentId}/events` (stand-in for a provider webhook) | Bearer-authenticated and requires the finance management permission: **no provider signature scheme is implemented**, because no real gateway is integrated. Idempotent on `payment_events.provider_event_id` (a repeated id answers `ALREADY_PROCESSED`); the event, not a client call, moves a payment to SUCCEEDED or FAILED. See "duplicate payment webhook" in the matrix. |
| `POST /ai/explain` | `STUDENT` (self) or `FACULTY`/`COLLEGE_ADMIN` (advisee, via tool scope). Returns `202 Accepted` + a job id (async — canonical spec §14 "AI: asynchronous queued/running/completed"), not a synchronous answer; client polls or subscribes via WebSocket for the `AIJobCompleted` status. |

### Notifications (implemented)

Any signed-in person; the caller is always the owner, no user or college id is accepted anywhere.

| Method & path | Notes |
|---|---|
| `GET /notifications?unreadOnly=false&limit=20` | The caller's own notifications, newest first (`limit` 1 to 50). Returns `{items:[{id,type,title,message,link,createdAt,readAt}], unreadCount}`; `unreadCount` covers all unread, not only the page. `link` is an in-app `/app/...` path or null. Inside a college only that college's rows are returned. |
| `POST /notifications/{id}/read` | Marks one read (idempotent; the read time does not move). **404** if the id is not the caller's, exactly as for an id that does not exist. |
| `POST /notifications/read-all` | Marks all of the caller's unread read; returns `{updated}`. |
