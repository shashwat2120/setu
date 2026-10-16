# Sprint 6A.1: extraction coupling audit

Measured from the Sprint 5 code and database, not taken from the candidate list in
[sprint6-candidate-services.md](sprint6-candidate-services.md). It validates or revises those boundaries, maps every table to one owner,
freezes the contracts between modules, and defines who owns which event. The numbers come from the compiled classes
(`ModuleContractFreezeTests`), the live schema (`TableOwnershipTests`) and `backend/tools/coupling-report.js`; the first two are tests, so
the findings below cannot quietly stop being true.

## 1. Result in one page

- **The module graph is acyclic**, but grouped into the 13 candidate services it was not: before this pass 11 of them sat in one dependency
  cycle, held together by four couplings that did not need to exist. All four are fixed (section 3). **The last cycle,
  Academic ↔ Identity, is resolved** (section 4, Sprint 6A.2 and 6A.3): the coupling report now shows no cycle between services, and Academic has
  no outgoing dependency.
- **The candidate boundaries mostly hold.** One is revised: **Record does not become its own service; it folds into Assessment**
  (section 6). One is not a candidate yet: **Analytics** has no code and no tables.
- **Every table has one owner** (93 tables, each in its owning module's schema, none written by another module's code). 34 foreign keys
  cross a service boundary and cannot survive separate databases; they are listed and frozen (section 5).
- **Academic is the hub** (nine services depend on it) and **Identity is the other** (nine depend on it). Their APIs and events are the
  contracts to get right first.
- **Academic published no events at all.** The services that keep a view of who is enrolled where (Learning, Attendance, Student Success,
  Record, Notification) had nothing to build it from. Eight events for enrollment, sections and attendance sessions are now emitted; 30 catalogued events still are not (section 7).
- **Safest first extractions:** Finance and Career (each depends only on Academic, lightly). **Riskiest:** Student Success (reads from five
  services) and the Identity/Academic pair.

## 2. How it was measured

| What | How | Where it stays true |
|---|---|---|
| Module → module dependencies, and which other modules' domain classes are used | ArchUnit over the compiled classes (a class belongs to the module named by its package under `com.setu`; `shared.admin` and `shared.seed` are separate nodes because they depend on modules, unlike the rest of `shared`) | `ModuleContractFreezeTests` against `module-contracts.txt` |
| Hidden dependencies through shared contracts (a module that uses `shared.X` depends on whichever module implements it) | `backend/tools/coupling-report.js` | the report, rerun after a change |
| Service-level graph, fan-in/out, cycles | same script, with the modules grouped into candidate services (Record folded into Assessment) | the report |
| Table ownership | the live schema against `table-owners.txt`; every JPA entity must map only its own module's schema | `TableOwnershipTests` |
| Foreign keys across services | `pg_constraint`, against `cross-service-foreign-keys.txt` | `TableOwnershipTests` |
| Events emitted | the event classes and `OutboxEventWriter` calls, against `kafka-event-catalog.md` | section 7 (by hand; no test yet) |

Regenerating after a deliberate change: `./mvnw test -Dtest='ModuleContractFreezeTests,TableOwnershipTests' -Dfreeze.update=true`, then review
the diff of the three files in `backend/src/test/resources/`. The tests also fail when frozen coupling *disappears*, so the lists can only
shrink unless someone decides otherwise.

## 3. What the audit found and fixed

| # | Finding | Evidence | Fix |
|---|---|---|---|
| F1 | **Academic depended on the AI service** (`institution` used the embedder that lived in `aiexplain`), while AI depends on Academic for its tools: a cycle | contract edge `institution → aiexplain` | The embedders moved into the shared kernel (`shared.ai`) as a library, so any service that needs embeddings uses them directly. Test: nothing depends on `aiexplain`, and `institution` does not |
| F2 | **Identity depended on Academic** twice: `/identity/me` assembled the caller's student/faculty/guardian ids from the directory, and the guardian-delegation check lived in identity | edges `identity → directory`, `identity → guardianaccess` | Profile ids are served by `GET /directory/me/profiles` (self-only, refused unless the caller holds a role in that college) and the frontend asks both; the delegation adapter moved to `guardianaccess` with the grants it reads. Test: identity depends on no other module |
| F3 | **Attendance and Notification depended on each other**: attendance called the broadcast service; notification asked attendance who may subscribe | edges in both directions at service level | Attendance publishes an in-process `LiveBroadcastRequest` event; notification delivers it **after commit** (a rollback no longer produces a live update). Test: attendance does not depend on notification |
| F4 | **Enrollment, sections and attendance sessions emitted no events** | no `outbox` call in the enrollment module; `StudentEnrolled`, `EnrollmentDropped`… catalogued, never written | `StudentEnrolled`, `StudentWaitlisted`, `WaitlistConverted`, `EnrollmentDropped`, `CourseSectionOpened`, `CourseSectionCancelled`, `AttendanceSessionOpened`, `AttendanceSessionClosed`, each written in the same transaction as the change. Students are now notified when waitlisted or promoted. 11 + 2 tests |
| F5 | The notification tests counted on a quiet shared dev database | a running backend's consumer also reacted to test fixtures | The fixtures mark their own events handled; no behaviour change |

Before the fixes the service graph had one cycle containing Identity, Academic, Assessment, Attendance, Learning, Student Success, Record,
Finance, Career, Notification and AI. After: the single pair below.

## 4. The cycle that was left: Academic ↔ Identity (resolved in 6A.2 and 6A.3)

**Resolved.** The sections below are the original finding and the decision; the outcome is in [contracts/identity-service.md](contracts/identity-service.md)
and [contracts/academic-service.md](contracts/academic-service.md). Short version: the engine became the shared `AuthorizationEngine`; Academic
feeds it from a read model built from Identity's `UserRoleAssigned`/`UserRoleRevoked`/`PersonRegistered`/`PersonUpdated`; logouts and suspensions
reach it through the Redis revocation list; guardian grants are Academic's own data, checked in-process with no cache, so a revoked grant stops
working on the next request. The two direct `UserDirectoryService` calls were replaced by `TenantMembership`.

- **Academic → Identity (12):** every Academic module authorizes through `AuthorizationContext`/`TenantContext`, and `directory` resolves
  people through `PersonDirectory` (persons are Identity-owned, decision H1). Four foreign keys: `directory.student_profiles`,
  `faculty_profiles`, `guardians` → `identity.persons`, and `guardian_access_permissions` → `identity.permissions`.
- **Identity → Academic (1):** the authorization engine (`AuthorizationService`, in identity) asks `GuardianDelegationChecker` whether a
  guardian holds an active grant; guardian grants are Academic data (decision H2).

**Status after 6A.2** (kept for the record; 6A.3 then closed the items marked not done):

- Done: access tokens are ES256 and verifiable from `/.well-known/jwks.json` with no shared secret; Identity publishes login and account
  revocations to Redis and `RemoteTokenAuthenticator` (signature + revocation, fail closed) is the check every other service makes; role
  grants and revocations now emit `UserRoleAssigned`/`UserRoleRevoked`; `IdentityServiceApplication` starts the identity module alone, with
  guardian delegation denied when no checker exists.
- Not done, needed before Academic leaves the monolith (6A.3): the **role read model** that consumes those events (so Academic authorizes
  from a local copy), a choice of its staleness bound (it is the time to consume the event, since nothing else delays it), and the
  **guardian-grant** answer (call Academic, uncached or invalidated by an event; Identity may not cache a revoked grant).

**Decision proposed for 6A.2:** the authorization engine is a *library* every service embeds, not a service it calls per request. A service
checks a permission from the JWT plus a local read model of roles and permissions, fed by Identity events. The Identity *service* (accounts,
sessions, passkeys, tokens) then depends on nobody, which is what the frozen test already guarantees for the module. A guardian-delegation
check is a call to Academic (the owner of grants), cached for seconds. What 6A.2 must settle, because tests today assume immediacy:

1. **Role changes take effect immediately** (`revokingARoleEndsItImmediately`). With a read model this becomes "within the push delay";
   choose the delay and publish role-change events (none exist yet).
2. **Logout and suspension block an access token at once** (`AccessTokenRevocationTests`). Today that is two indexed queries per request in
   `LoginLivenessService`. Every service cannot query Identity's tables: Identity writes revoked login families to Redis (the spec's
   "session/denylist acceleration") and each service reads it, failing closed.
3. **Guardian grants:** the cache lifetime of a delegation answer is the bound on how long a revoked grant keeps working. Pick it from the
   guardian-revocation requirement ("immediate effect"): either no cache or an invalidation event.

## 5. Data ownership

**93 tables, one owner each** (`table-owners.txt`; the owner is the schema, the service is where that schema goes):

| Service | Tables | Schemas |
|---|---:|---|
| Academic | 34 | institution, catalog, enrollment, directory, guardian_access |
| Identity | 11 | identity |
| Assessment (with Record) | 10 | assessment, record |
| Finance | 7 | finance |
| Career | 7 | career |
| Learning | 6 | learning |
| Attendance | 6 | attendance |
| Student Success | 5 | risk |
| Notification | 4 | notification |
| Platform (replicated per service) | 3 | governance: `audit_logs`, `outbox_events`, `idempotency_keys` |

Every one of the 81 JPA entities maps a table in its own module's schema, and the only SQL that names another schema's table is the demo
seeder. Nothing writes another module's tables.

**Tables nothing uses (9).** The schema has them; no code reads or writes them: `record.student_academic_records`,
`record.student_academic_periods`, `record.transcripts`, `record.graduation_applications`, `catalog.degree_progress`,
`enrollment.class_students`, `enrollment.class_faculty`, `notification.delivery_attempts`, `notification.notification_preferences`. GPA,
transcripts and degree progress are computed on read from grades and enrollments. They are owned and left alone: some are planned (delivery
tracking, preferences), and dropping them is a product decision, not an extraction one.

**Foreign keys that cross a service boundary: 34** (`cross-service-foreign-keys.txt`), plus 72 that only point at a tenant
(`institution.colleges`) or a user (`identity.users`), which become plain identifier columns. The 34, by pair:

| Pair | FKs | What each becomes |
|---|---:|---|
| Assessment → Academic (incl. Record) | 11 | `grades.enrollment_id`, `assessments.section_id`, student and period ids: check on write through the Academic API, or from the enrollment read model |
| Attendance → Academic | 5 | `enrollment_id`, `section_id`, `meeting_pattern_id`, `faculty_id` |
| Learning → Academic | 4 | `section_id`, `student_id` |
| Finance → Academic | 4 | `student_id`, `program_id`, `academic_year_id` |
| Academic → Identity | 4 | `person_id` ×3, `permission_id` |
| Career → Academic | 3 | `student_id` |
| Student Success → Academic | 2 | `student_id` |
| **Learning → Assessment** | **1** | **`assignment_submissions.grade_id` → `assessment.grades`**: the one place two feature services are coupled in the database but in no code |

The default replacement is the same everywhere: keep the column, drop the constraint, validate the id when it is written (through the owner's
API, or a local read model fed by the owner's events), and tolerate an id the owner has since removed. `learning → assessment` is
event-driven: Learning records the grade id it learns from `GradePublished`.

## 6. Candidate services: verdicts

Fan-out is how many other services a service depends on; fan-in how many depend on it (Record counted inside Assessment).

| Candidate | Verdict | Fan-out / in | Evidence and conditions |
|---|---|---|---|
| Identity | **Confirmed** | 1 / 9 | Zero module dependencies (test). Owns persons. The 1 is the authorization cycle (section 4) |
| Institution/Academic | **Confirmed as one service**, not split | 1 / 9 | `enrollment → catalog` uses 9 types and 6 foreign keys; `catalog → institution` 13 FKs; 44 cross-schema foreign keys stay inside it. Splitting would turn those into network calls with no scaling reason. `directory` (people) is the base nine services reference. `guardianaccess` is **not** an independent service: it depends only on `directory` and is reached only through the authorization contract |
| Learning | **Confirmed** | 2 / 2 | Depends on Academic (enrollment and section lookups, student ids). No code edge to Assessment; one FK (section 5) |
| Assessment | **Confirmed, absorbing Record** | 2 / 2 | Read by Student Success and AI |
| **Record** | **Revised: fold into Assessment** | | No code uses any of its four tables. It is a read-side computation over grades (3 types from assessment) and enrollments, courses and people (10 from Academic): the same direction Assessment already depends on Academic. Folding into **Academic** instead would make Academic depend on Assessment's grades, a new cycle. Reconsider only if it starts persisting transcripts |
| Attendance | **Confirmed** | 2 / 3 | Academic (7 types, 5 FKs). Live updates are now an event, so no call to Notification. Highest event rate: `AttendanceRecorded` |
| Student Success | **Confirmed, extract late** | 5 / 1 | Reads features from Learning (6 types, 4 of them domain classes), Assessment, Attendance, Academic: five services. It needs events first (`AssignmentSubmitted` and the learning events are not emitted) |
| Career | **Confirmed; extract early** | 2 / 1 | Depends only on Academic (`student_id` ×3, institution) |
| Finance | **Confirmed; extract early** | 2 / 1 | Depends only on Academic (4 FKs). Payment isolation is a reason of its own |
| Notification/Realtime | **Confirmed** | 3 / 0 | Needs Identity (token check), Academic (student → user, today by synchronous lookup) and Attendance (subscription authorizer). The consumer works for 10 event types |
| AI | **Confirmed as a leaf** | 8 / 0 | Calls every service through its tools (the secure tool boundary of 6B.3). 27 uses of other modules' domain classes (section 8) must become API contracts |
| Analytics | **Not a candidate yet** | 0 / 0 | One `package-info`, no tables, no consumers. Nothing to extract until it builds a projection |
| API Gateway | New | | Route table below |

**Route table for the gateway** (path prefix → service), from the controllers that exist:
`/auth`, `/identity` → Identity · `/institution`, `/catalog`, `/enrollment`, `/directory`, `/guardian-access`, `/admin` → Academic ·
`/learning` → Learning · `/assessment`, `/record` → Assessment · `/attendance`, `/staff-attendance` → Attendance · `/risk` → Student
Success · `/career` → Career · `/finance` → Finance · `/notifications`, `/ws` → Notification · `/ai` → AI · `/governance` (audit log read) →
open: each service will write its own audit log; the platform-wide read view needs an owner (proposed: Identity, as the platform's
administrative surface). `shared.admin` (admitting a student: account + person + profile) is an orchestration across Identity and Academic;
it goes to Academic, calling Identity's API.

## 7. Events

**Ownership:** an event belongs to the service that owns the aggregate; the producer is named in the event (`producer`). Emitted today by
module: assessment (`GradePublished`), attendance (`AttendanceRecorded`, **2 session events, new**, 8 staff-attendance events), career
(`PlacementRecorded`), directory (`StudentAdmitted`), **enrollment (6, new: 4 enrollment, 2 section)**, finance (3 payment events), identity
(5), risk (4). 33 events are emitted: 32 of the 62 catalogued (30 are not) and one, `UserReactivated`, that the catalog lacks.

**Not emitted, and who will need it.** The consumers of Sprint 6 build their own view of other services' data from these; the blocking ones
are marked.

| Needed by | Event | Status |
|---|---|---|
| Learning, Attendance, Student Success, Notification | `StudentEnrolled`, `EnrollmentDropped`, `StudentWaitlisted`, `WaitlistConverted` | **now emitted** |
| Learning, Attendance | `CourseSectionOpened`, `CourseSectionCancelled` | **now emitted**. `CourseSectionCancelled` lists the enrollments still active; **cancelling a section does not yet drop them** (the catalog expects Academic to cascade the drops itself), an open behaviour gap |
| Student Success | `AssignmentSubmitted`, `AssignmentGraded`, `AssessmentAttemptSubmitted`, `AssessmentCompleted` | **blocking** for its feature pipeline |
| Attendance read models | `AttendanceSessionOpened`, `AttendanceSessionClosed` | **now emitted** |
| Student Success | `AttendanceCorrected` | blocking for its trend (a corrected record changes the percentage) |
| Assessment (Record) | `EnrollmentCompleted`, `PeriodClosed`, `GraduationApproved/Rejected`, `StudentGraduated` | blocking for transcripts and degree progress |
| Notification | `RegistrationOpened/Closed`, `WaitlistOffered/Expired`, `AssessmentPublished`, `StudentSuspended`, `CollegeSuspended` | not blocking |
| Identity consumers | role granted / revoked (not even catalogued) | **blocking** for the authorization read model (section 4) |
| Others | `AcademicYear*`, `Course*`, `StudentProgram*`, `Override*`, `Transcript*`, `AIJobCompleted` | not blocking |

**Contract gaps already known (6B.1):** the envelope has no `producer` or `schema_version` field; `correlationId` is a fresh id per event, not
the request's, so a request cannot be traced across services; `UserReactivated` is emitted but not catalogued.

## 8. Cross-module domain classes (frozen)

27 uses, across 11 module pairs, of another module's **domain classes** (entities and records), rather than its application API. Across a
service boundary each needs a DTO, an API contract or an event payload. They are frozen in `module-contracts.txt` (`domain` lines), so the
list cannot grow:

| User → owner | Types |
|---|---|
| aiexplain → learning | Assignment, CourseModule, LearningProgress, LearningProgressState, Lesson, LessonType |
| aiexplain → assessment | Assessment, Grade |
| aiexplain → risk | RiskBand, RiskFactor, RiskScore |
| aiexplain → finance | StudentCharge, StudentChargeStatus |
| risk → learning | AssignmentSubmission, AssignmentSubmissionStatus, LearningProgress, LearningProgressState |
| risk → assessment | Grade |
| record → enrollment | CourseSection, Enrollment, EnrollmentStatus |
| record → assessment, catalog | Grade, Course |
| enrollment → catalog | AcademicPeriod, PrerequisiteRuleType |
| attendance → directory | FacultyProfile, FacultyStatus |

Fewest to fix per service: Attendance (2), Academic (2), Record/Assessment (5). The AI and Student Success sets (18 of the 27) are the work of
giving Learning, Assessment, Risk and Finance read contracts.

## 9. Two things in the code that block extraction until moved

- **The demo-data seeder** (`shared.seed`) depends on twelve modules and is the stated reason for the `@NamedInterface` on 32 services and for 36 public `*ForSeed` methods:
  services widened their API only so it could call them. It cannot live in any one service. It becomes a separate demo loader
  that uses each service's API, or a seed script per service.
- **`shared.admin`** (student, faculty and guardian onboarding) orchestrates Identity and Academic in one transaction. Across services that is
  a saga: create the account, then the person, then the profile, with compensation. Assigned to Academic.

## 10. Recommended order

1. **Pre-work (done in this pass):** F1 to F4, the frozen contracts.
2. **6A.2 Identity (done):** ES256 + JWKS, Redis revocation list, role-change events, standalone entry point.
3. **6A.3 Academic (done for the service boundary):** role/person read model, kernel authorization engine, standalone entry point, no Academic-to-Identity
   dependency. Not done: the onboarding saga, section-cancel cascade, per-service databases (6B.2) and the other services' own read models (each needs
   the role and, for guardian access, grant data when it is extracted).
4. **6A.4 gateway and discovery (done):** [contracts/gateway.md](contracts/gateway.md): servlet gateway (no Spring Cloud train supports Boot 4.1), Redis-announced and configured instances with passive health, edge authentication, rate limiting, error envelope; `GatewayRouteCoverageTests` keeps the route table and the controllers in step. **Config server (done):** [contracts/config-server.md](contracts/config-server.md): per-application tokens, secrets kept in a git-ignored directory, `local` and `demo` profiles, a `setu-config:` import for services, and a test that fails if a secret is committed.
5. **6A.5 Learning, Assessment, Attendance (done for the services; Record, the live WebSocket proxy and the synchronous guardian check are open):** [contracts/extracted-services.md](contracts/extracted-services.md): one `AcademicDirectory` port, a per-service copy of Identity and Academic facts fed by events, eight module edges and a leaked domain class removed, realtime support moved to the shared kernel.
6. **Finance and Career** (leaf services, Academic only): the first feature services out, and the proof that the pattern works.
6. **Attendance, Learning, Assessment (with Record)**, with the missing learning and assessment events.
7. **Student Success** last of the core services; then **Notification/Realtime** and **AI** in 6B.3. **Notification/Realtime (done, 6B.3):** [contracts/notification-service.md](contracts/notification-service.md): recipients from a local copy through the `AcademicDirectory` port (the module lost its edges to `directory` and `enrollment`), live delivery on a per-user topic only that user may subscribe to, `V85`/`V86`. **AI (done, 6B.3):** [contracts/ai-service.md](contracts/ai-service.md): the advisor (`aiexplain`) has no datasource and depends on no other module; the former tool logic is the `aifacts` module behind `shared.aifacts.AiFacts`, called over the network with the user's own token; background jobs with queued/running/completed status on the owner's own topic. The 27 uses of other modules' domain classes by `aiexplain` (section 8: 13 of the 27) are gone with the move.
8. **Analytics** when it has something to project.

## 11. What was not measured

- **Runtime coupling:** which calls actually happen at what rate. This is static structure; the load and Kafka-lag checks are 6C.2.
- **The frontend's coupling to the API:** each page calls whichever endpoints it needs through the gateway's future routes. Pages that join
  data from two services (for example the student dashboard) will need either a composing endpoint or two calls.
- **Query-level coupling:** joins in the repositories were checked only for table ownership, not for reads that happen to be efficient
  because the data is in one database (the Student Success feature pipeline is the likeliest to feel it).
