# Data ownership and synchronous calls (Sprint 6B.2)

Every table has exactly one owning service; no service reads or writes another's tables; what one service needs of another's data it holds as
an event-fed copy (the read models in [extracted-services.md](extracted-services.md)) or, where a stale answer is not acceptable, asks for at
the moment it matters. This document is what is enforced, what is deliberately left, and how a service's tables leave the shared database.

## 1. One owner per table

* `backend/src/test/resources/table-owners.txt` lists every table with its owner (the table's schema is its owner). `TableOwnershipTests`
  fails when a table is missing from it, when a JPA entity maps a table outside its own module's schema, and when a foreign key crosses a
  service boundary that is not on the frozen list.
* No cross-service joins: a service queries its own schema only. The read-model tables (`rm_*`) are in the schema of the service that uses
  them and are owned by it, though the facts in them belong to someone else; they change only by applying that owner's events.

## 2. Foreign keys

The 34 constraints that tied one service's table to another's were frozen by the 6A.1 audit. `V83` drops the **17** whose child table
belongs to a service that now runs on its own: `directory` and `guardian_access` (Academic, towards Identity's persons and permissions),
`learning`, `assessment` and `attendance` (towards Academic's sections, enrollments, meetings, students and faculty, and Learning towards
Assessment's grades). The column and its index stay; only the constraint goes.

`V84` removes the rest of what leaves a service: the **tenant and user references** (`institution.colleges`, `identity.users`) that nearly
every table carries. The 6A.1 list left them out as "plain identifiers", but a constraint is a constraint: the first rehearsal of
splitting Learning off failed on `learning` -> `institution.colleges`. V84 is a rule, not a list: any foreign key from a table of Identity,
Academic, Learning, Assessment or Attendance to a table of a different service goes, whatever it is called. Constraints inside one service
stay (Academic's own catalog, enrollment and directory schemas still reference each other).

`V86` applies the same rule to the Notification service (6B.3) once it was extracted: its three constraints to `institution.colleges` and
`identity.users` go ([notification-service.md](notification-service.md)).

**17 remain**, all on tables of modules that still run in the core: `record` (8), `finance` (4), `career` (3) and `risk` (2). They are
listed in `cross-service-foreign-keys.txt`, may only shrink, and go in the same change that extracts the module.
`TableOwnershipTests.noExtractedServiceIsTiedToAnotherServicesTableByAForeignKey` queries the live database and fails if any constraint
(tenant and user references included) reappears on an extracted service's table.

What the constraints guaranteed is replaced by two things, not by hope:

1. **At write time** the owning service checks the reference through the same port it always used for authorization and lookups
   (`AcademicDirectory`, `PersonDirectory`, `CourseSectionDirectory`), answered from its local copy. A reference to something its copy does not
   know is refused. Directory validates the person of every profile; a guardian grant can only carry a permission from the fixed
   guardian-visible set.
2. **After the fact**, `ReferenceReconciler` counts, every 10 minutes, the rows whose reference has no counterpart in the local copy
   (metric `setu.reconciliation.orphans{service,reference}`, plus a warning in the log). It changes nothing: repairing data on a guess is worse
   than the inconsistency, and a non-zero count is for a person to look at. The references it checks are listed in the class.

What is **not** replaced: Learning's `assignment_submissions.grade_id` (a grade is Assessment's) is not reconcilable from a local copy, because no
copy of grades is kept. It is only ever set from a published-grade event, and an unknown id there is a data problem to find by hand.

## 3. Synchronous calls: only where a stale answer is unacceptable

Everything crosses services by event except one question, and it is on the security path:

> **Does this guardian hold this permission for this student, right now?**

The read model of the grants lags a revocation by the seconds it takes the event to be applied, and "revocation takes immediate effect" is a rule.
So in the extracted services (`setu.guardian-verify.url` set to Academic's address):

| Local copy says | Academic is asked? | Result |
|---|---|---|
| no | no | **no** |
| yes | yes: active | **yes** |
| yes | yes: not active | **no** (the copy was behind) |
| yes | cannot be asked (timeout, 5xx, bad answer, circuit open, bulkhead full, wrong token) | **no**, never the stale yes |

The guardian sees "not available right now" during an Academic outage, which is true. There is **no fallback value**: a fallback that said yes
would reintroduce the bug, and one that invented anything else would be fake data standing in for an answer. Students, faculty and
administrators never take this path.

How the call is made:

* `GET /internal/guardian-grants/verify` on Academic (`InternalGuardianGrantController`), answered from the grants in Academic's own transaction.
* Authenticated by `X-Internal-Token` (`setu.internal.token`, delivered by the config server), a header of its own so it can never be mistaken for a
  user's bearer token; compared in constant time; **with no token configured every internal call is refused**. A user's token, even an
  administrator's, gets 403 on it, and the service token opens nothing outside `/internal/`.
* The gateway answers 404 for any `/internal/**` path before it looks at its routes, so the catch-all route to the core cannot carry one in.
* Apache HttpClient 5 (blocking), connect timeout 300 ms, response timeout 800 ms, no retries; wrapped in `GuardedCall`: a circuit breaker
  (opens at 50% failures over at least 10 of the last 20 calls, 10 s open) and a bulkhead (20 concurrent callers, no queue). The correlation id
  travels with the call. Counters: `setu.guardian.grant_verification{outcome}` and `setu.sync_call.unavailable{call,reason}`.
* Resilience4j's core libraries are used directly rather than OpenFeign plus the Spring Boot starters: this is the only synchronous
  service-to-service call, so a declarative client framework would be more machinery than call. If a second synchronous call ever appears,
  add it behind `GuardedCall` the same way, with the same rule: no invented answers.

Tests: `GuardianGrantVerificationTests` (decision table; the client against a real socket for every kind of failure including a timeout),
`GuardedCallTests`, `AcademicServiceWiringTests` (the endpoint's security), `GatewayFilterTests` (never routed).

## 4. Taking a service's tables out of the shared database

`infra/db/split/split-service.sh <Service> <target-database>` copies exactly the schemas a service owns, structure then data, plus the Flyway
history so the service's own migration run starts at the monolith's version and applies only what follows. The source is only read; the target
must not exist, so a half-finished split can never be mistaken for a finished one. It then verifies:

* the row count of every copied table equals the source's;
* the target contains no table the service does not own;
* no foreign key in the target points outside the owned schemas.

Any failure exits non-zero and drops the target again. Because the structure is dumped schema by schema, a constraint to another service's
table fails the copy, which is why the foreign keys had to go first (section 2). It also copies the source's extensions (`citext`, `btree_gist`,
`vector`), which the owned tables use.

Rehearsed on the compose database for Identity (11 tables), Academic (38), Learning (18), Attendance (18) and Notification (16), all with equal row counts.
**Assessment** owns `record` too, and Record still runs in the core with its foreign keys into Academic, so that split is refused (it fails on
the missing `directory` schema) unless `SETU_SPLIT_EXCLUDE=record` leaves Record behind; with it, 18 tables copy cleanly. The read-model tables are copied with the rest and then kept
current by replay: the consumer's group starts from the earliest offset, and the processed-event table makes the overlap harmless.

## 5. Honest limits

* The AI service has no database at all (not even a replicated audit table): its only write is an audit record sent to the data service ([ai-service.md](ai-service.md) section 3).

* Record, Finance, Career and Risk are still in the core and keep their constraints (section 2) until they are extracted.
* The reconciler counts orphans; it does not prevent them. The write-time check prevents; the count tells you if it was ever bypassed.
* `split-service.sh` is a development and rehearsal tool for the compose Postgres. A production cut-over also needs a write freeze on the
  owned schemas, which is an operations decision, not something a script should assume.
