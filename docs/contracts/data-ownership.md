# Data ownership and synchronous calls (Sprint 6B.2)

Every table has exactly one owning service; no service reads or writes another's tables; what one service needs of another's data it holds as
an event-fed copy (the read models in [extracted-services.md](extracted-services.md)) or, where a stale answer is not acceptable, asks for at
the moment it matters. This document is what is enforced, what is deliberately left, and how a service's tables leave the shared database.

## 1. One owner per table

* `backend/src/test/resources/table-owners.txt` lists every table with its owner (the table's schema is its owner). `TableOwnershipTests`
  fails when a table is missing from it, when a JPA entity maps a table outside its own module's schema, and when a foreign key crosses a
  service boundary that is not on the frozen list.
* **The database enforces it too (section 1a).** Each service logs in as its own database role, able to use only its own schemas.
* No cross-service joins: a service queries its own schema only. The read-model tables (`rm_*`) are in the schema of the service that uses
  them and are owned by it, though the facts in them belong to someone else; they change only by applying that owner's events.

## 1a. One database login per service

The tests above keep ownership by convention. `infra/db/roles/service-roles.sql` makes the database itself refuse: it creates one login per service
(`setu_identity`, `setu_academic`, `setu_learning`, `setu_assessment`, `setu_attendance`, `setu_notification`; the gateway and the AI service have no database)
and grants each one only the schemas that service owns. The core keeps the owner login, because it runs the migrations and still holds Record, Risk, Career and Finance.

| A service login can | It cannot |
|---|---|
| read and write the tables of the schemas it owns, including tables later migrations add (default privileges) | read or write another service's tables, or the core's (`finance`, `career`, `risk`, `record`): PostgreSQL answers "permission denied" (42501) |
| read the outbox, mark events published, add and read audit records, use idempotency keys (`governance`) | delete an outbox event, or change or remove an audit record (the audit log is append-only to every service) |
| read and write Spring Modulith's event log (`public.event_publication`) | read the migration history, create or alter or drop or truncate anything, create a schema, or become a superuser |

How it is applied: the compose file runs `db-roles` once, after the core has migrated and before the services start (`docker-compose.services.yml`); each service's
`DB_USER` is its own role. The script converges: run again it takes back any grant that is no longer in it. **Held by tests:** `ServiceSqlStaysInItsSchemasTests` fails if any service's hand-written SQL names a schema it does not own, and `ServiceDatabaseRolesTests` connects as each
role and tries each of the refusals above (including every service against every other service's schema), and checks the script and `table-owners.txt` agree
about who holds what.

Found by switching the services over, and fixed: the first run failed the attendance scan with a 500, because Spring Modulith keeps its own log of published application
events in `public.event_publication` and every process writes it; it is now granted. Nothing else a service did reached outside its own schemas: the
other five ran the whole smoke flow and the Learning and Notification calls with no refusal.

**What this is not.** It is still one PostgreSQL database, one server, one set of backups, and the same password for every role in development
(`SETU_SERVICE_DB_PASSWORD`; a real deployment sets it per environment, ideally a different one per service). The outbox, audit log and idempotency tables are
shared by design until each service has its own database, and Modulith's event log is shared in the same way. Separate databases are section 4 and are not done
for any service yet.

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

Everything crosses services by event except two questions. The first is on the security path:

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

## 3a. The second synchronous question: attendance counts

Once Attendance has a database of its own (section 4c), the three places that used to read its records in-process (the risk engine's attendance feature, the AI advisor's
attendance tool, and Career's standing for placement eligibility) ask the Attendance service instead: `GET /internal/attendance/counts?enrollmentIds=...` (`InternalAttendanceCountsController`),
answered from the attendance records in the service's own transaction. A calculation needs the current number, not a copy a few seconds behind, and a copy of every record in
three other services is the wrong cost for one count.

* It is internal: the service-to-service token only, no route at the gateway, no per-person check (the caller has authorized its own outer operation, as with the in-process method it
  replaced).
* The caller's side is the port `AttendanceCounts`: in the monolith the attendance module answers in-process (`LocalAttendanceCounts`); with `setu.attendance.url` set the answer is
  `RemoteAttendanceCounts`, behind a circuit breaker and bulkhead, with short timeouts.
* **No answer is never zeros.** A malformed, incomplete, impossible (more present than recorded) or missing answer, a timeout, a 5xx and an open circuit are all
  `AttendanceCounts.Unavailable`: the risk event is retried (and dead-lettered after three tries) rather than scored on guessed attendance, the advisor tells the person that
  attendance is unavailable instead of giving a figure, and an HTTP caller gets `503 DEPENDENCY_UNAVAILABLE` in the standard envelope.

The same port pattern serves Assessment (section 4d): `AssessmentFacts` gives the risk engine, the AI advisor and the academic record (transcripts, GPA) a student's current grades,
each with the title, code and weight of the component it grades (`GET /internal/assessment/grades`), and the student-visible assessments of a section for the advisor's deadlines
(`GET /internal/assessment/visible`). Both answer one entry per id asked, so "none" is told apart from "not answered"; the HTTP plumbing is shared with the Attendance reader
(`InternalJsonClient`), and every failure is a `DependencyUnavailableException` that one handler turns into `503 DEPENDENCY_UNAVAILABLE`. One deliberate change from the
in-process call: the advisor used to name a grade only "as far as the caller is allowed to read" its assessment; it now gets the name from the owner for the grades the caller was
already authorized to see (so a guardian with the grades permission now sees the assessment's title next to the grade).

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

## 4a. The first service on a database of its own: Notification

Notification runs on **its own PostgreSQL server** (`postgres-notification`, its own volume), not as a login on the shared one. That server holds exactly three schemas and
20 tables: `notification` (16: notifications, delivery attempts, preferences, processed event ids and the `rm_*` read model), its **own empty copy of the platform tables**
(`governance`: outbox, audit log, idempotency keys), and Spring Modulith's event log (`public.event_publication`). Nothing of any other service is on it.

How it got there, repeatably: the compose one-shot `db-notification-init` runs `infra/db/split/provision-service-db.sh notification notification`. It waits for the core
(which creates the tables), loads the notification schema **with its data** and the platform tables **without** in a **single transaction** (the last table in the load is
`public.event_publication`, so its existence means the load finished: a failed run leaves nothing that looks finished), compares the row count of every copied table with
the source, refuses a copy that contains any schema that is not the service's, and applies the service's login (section 1a). Run again on a loaded database it only
re-applies the grants. Because the read-model tables and the processed-event ids come across, the service carries on from where the monolith stopped: Kafka's committed
offsets are the same group, so nothing is replayed twice and nothing is lost.

The one other change the cut-over needed: **the core stopped consuming for Notification** (`APP_NOTIFICATION_CONSUMER_ENABLED=false`). The core still contains the notification
module and would otherwise join the same Kafka consumer group; an event it handled would be written to the old copy in the shared database, where nobody reads it, and
half of all notifications would silently disappear.

**What it shows, run for real:** an event published by the Assessment service travels through Kafka to the Notification service and becomes a row in *its* database
(41 rows there, the old copy unchanged at 40); the student reads it back through the gateway; and when the Notification service's database server is stopped, only
Notification fails (the gateway answers 504 in the standard envelope) while Identity, Academic, Learning and Attendance keep answering, and it recovers by itself when
the server returns.

## 4b. A service's own migrations

A database of its own is only independent if it can change by itself. The Notification service therefore runs **Flyway against its own database at start-up**, from two folders and
never from the monolith's (`infra/db/README.md`, "A service's own migrations"):

* `infra/db/platform/`: changes to the tables every database carries a copy of (the outbox, audit log, idempotency keys, Modulith's event log). The core applies it to the shared
  database and **every** service with a database of its own applies it to its own, so a platform change reaches all of them. None exist yet; the folder is wired in everywhere so
  the first one needs no further plumbing.
* `infra/db/services/notification/`: changes to what Notification owns. The service applies it to its own database, and **the core applies the same file to the shared database's
  copy** (it still contains the module and validates its entities against that copy), so the two stay in step and a version means the same thing in both histories. The first is
  `V104__notification_unread_index.sql`.

Rules, held by `ServiceMigrationsTests` without a database: one version number across all folders (Flyway also refuses a duplicate at start-up); the new folders start above
103, the version the shared database stood at when the first service was split off; a service's files touch only that service's schemas and may not create or drop schemas,
databases or roles or change grants; platform files touch only the platform tables; and the image, the core's configuration and the service's compose entry each list every
folder they must.

The service's database starts with a Flyway history of **one baseline row**, written by the provisioning job: the highest version in the service's folder that the source had
applied when the copy was taken, or the split version (103) when none. The monolith's history is never copied. Flyway runs as the database owner of that server (it has to create
things, which the service's own login cannot); the service itself keeps running as `setu_notification`, and anything a migration creates is covered for that login by the default
privileges of section 1a.

## 4c. The second service on a database of its own: Attendance

Attendance runs on **its own PostgreSQL server** (`postgres-attendance`, its own volume) with the same shape as section 4a: the `attendance` schema and data, its own outbox, audit log and
idempotency keys, Modulith's event log, and its own login. It migrates itself from `infra/db/platform` and `infra/db/services/attendance` (section 4b); the first migration is
`V105__attendance_sessions_by_section.sql`, an index for the newest-first listing of a section's sessions that nothing indexed.

What made it possible, and what Notification did not need: **nothing outside the attendance module reads its tables any more.** Before the split the risk engine, the AI advisor and Career's
standing called the attendance module in-process, so moving the data would have given them the stale copy in the shared database. They now go through the `AttendanceCounts` port
(section 3a), and the frozen module-contract list lost two edges (`risk -> attendance`, `aifacts -> attendance`).

The load is done while the Attendance service is stopped (a copy taken while it writes would miss what it writes next), then the service starts and migrates. The core has no
attendance consumers or schedules of its own, so there was no second consumer to switch off, unlike Notification.

## 4d. The third service on a database of its own: Assessment

Assessment runs on **its own PostgreSQL server** (`postgres-assessment`, its own volume), the same shape as sections 4a and 4c, and migrates itself from `infra/db/platform` and
`infra/db/services/assessment`. The first migration is `V106__assessments_by_section.sql`: `assessments` had no index on `section_id`, which every section view and every
deadline lookup filters by.

As with Attendance, it was possible because nothing outside the assessment module reads its tables any more: the risk engine, the AI advisor and `AcademicRecordService` went
through the `AssessmentFacts` port first, and the frozen module-contract list lost three module edges (`risk`, `aifacts` and `record` to `assessment`) and the four uses of
`Grade` and `Assessment` types behind them. The `record` schema stays in the core (Record is not extracted), so only the `assessment` schema moved. The load was done with the
service stopped.

## 5. Honest limits

* **Only Notification, Attendance and Assessment have databases of their own.** Identity, Academic and Learning still share the one PostgreSQL database, each as its own login (section 1a).
* **Attendance: the shared database still holds its old copy** (unused; the core validates its attendance entities against it at start-up), kept in step by the same migration files. The seed
  data (`DemoDataSeeder`) still writes attendance through the monolith's module, so a fresh install seeds the shared database and the provisioning job copies it across.
* **The shared database still holds a copy of the notification schema, now kept in step by the same migration files (section 4b), not left stale.** It is unused (nothing writes
  it since the core stopped consuming for Notification) and has to stay while the core contains the module and validates its entities at start-up. It goes when the core stops
  containing the module.
* **A new service's database still starts from a copy.** A service's migrations begin after the split (above 103); the earlier history of its schema is in the monolith's
  folder. A database could not yet be built from nothing out of a service's folder alone.
* **Flyway runs with the database owner's credentials** inside the service's container; a stricter setup gives it a dedicated migration role that owns the database's objects.
* **A database outage is not free of cost to events.** A failing event is retried three times and then dead-lettered ([kafka-contracts.md](kafka-contracts.md) section 5), so events that
  arrive while the Notification database is down for longer than that window go to `setu.*.DLQ` and need replaying. The outage test above sent no events during the outage.
* **The failure looks like a slow 504, not an instant refusal:** the service waits out its connection pool timeout before the gateway gives up on it.
* Same PostgreSQL version and the same development password (`SETU_SERVICE_DB_PASSWORD`); one Docker host.

* Every service runs as its own database login (section 1a); all but Notification, Attendance and Assessment still share one PostgreSQL database, so for them the platform tables (outbox, audit log, idempotency keys)
  and Modulith's event log are shared. A process publishes any `NEW` outbox row, whichever service wrote it; that works because every consumer is idempotent, and ends when
  each service has its own database and so its own outbox.

* The AI service has no database at all (not even a replicated audit table): its only write is an audit record sent to the data service ([ai-service.md](ai-service.md) section 3).

* Record, Finance, Career and Risk are still in the core and keep their constraints (section 2) until they are extracted.
* The reconciler counts orphans; it does not prevent them. The write-time check prevents; the count tells you if it was ever bypassed.
* `split-service.sh` is a development and rehearsal tool for the compose Postgres. A production cut-over also needs a write freeze on the
  owned schemas, which is an operations decision, not something a script should assume.
