# Learning, Assessment and Attendance services (Sprint 6A.5)

The three services that sit directly on top of Academic, each startable on its own (`LearningServiceApplication` :8084,
`AssessmentServiceApplication` :8085, `AttendanceServiceApplication` :8086), and the one mechanism that makes that possible: each keeps its
**own copy of the Identity and Academic facts it decides with**, kept current by events, so no request ever leaves the process to be authorized
or validated. Contracts for the services they depend on: [identity-service.md](identity-service.md), [academic-service.md](academic-service.md),
[gateway.md](gateway.md).

## 1. What they asked before, and what they ask now

Before, these modules called Academic's services directly (about ten distinct lookups across the three). Now they depend on one narrow port,
`shared.academic.AcademicDirectory`, and on `CourseSectionDirectory` (section to college), `AuthorizationContext`, `TenantContext` and
`GuardianDelegationChecker`. Eight module-to-module edges and one leaked domain class (`attendance` used Academic's `FacultyProfile`) are gone
from `module-contracts.txt`; the three modules import nothing from `directory`, `enrollment`, `institution` or `catalog`.

| `AcademicDirectory` question | Used for |
|---|---|
| `studentIdOfUser`, `studentOwner` | "is this signed-in user a student here", ownership of a student's data |
| `facultyIdOfUser`, `facultyTenant`, `facultyStatus` | "is this signed-in user faculty here"; staff self-service blocked unless ACTIVE |
| `isActivelyAssigned` | may this faculty member run this section's content, assessments, attendance |
| `activeEnrollmentId`, `enrollment`, `activeEnrollmentIds` | may this student see this section; which enrollment a grade or attendance record belongs to; who is absent when a session closes |
| `meeting` | does this meeting belong to this section and college |
| `timeZoneOf` | what calendar day it is for a college (staff attendance) |

In the monolith the port is answered by `enrollment.application.AcademicDirectoryAdapter` from Academic's own services. In an extracted service
it is answered by `ReadModelAcademicDirectory` from that service's copy. The decision logic above the port did not change, and the three
modules' existing suites (248 tests) pass unchanged on the port.

## 2. The copy

Per service, in its own schema (`learning`, `assessment`, `attendance`; migration `V81`), written only by the projectors, never by service code:

| Table | Fed by | Answers |
|---|---|---|
| `rm_role_grants`, `rm_role_permissions` | Identity: `UserRoleAssigned`, `UserRoleRevoked` (matrix copied by migration, parity-tested) | the shared authorization engine |
| `rm_students`, `rm_faculty` | Academic: `StudentAdmitted` (now carries `ownerUserId`), `FacultyProfileCreated`, `FacultyStatusChanged` | who a user is as a student or faculty member |
| `rm_faculty_assignments` | `FacultyAssigned`, `FacultyAssignmentEnded` | who teaches which section now (in force while `ends_at` is null) |
| `rm_enrollments` | `StudentEnrolled`, `EnrollmentDropped`, `EnrollmentCompleted` | who is in which section now (`active`), and which enrollments are completed (`completed`: not active, but still gradable: late components and corrections of the final grade) |
| `rm_sections` | `CourseSectionCreated`, `CourseSectionOpened`, `CourseSectionCancelled` | which college a section belongs to, from the moment it is created |
| `rm_colleges` | `CollegeCreated`, `CollegeUpdated` | time zone |
| `rm_meetings` | `MeetingPatternScheduled`, `MeetingPatternRemoved` | meeting to section |
| `rm_guardian_grants`, `rm_guardian_grant_permissions` | `GuardianAccessGranted`, `GuardianAccessRevoked` | whether a guardian may see a student's data |
| `rm_processed_events` | every projector | a redelivered event is a no-op |

**Convergence.** Every projected row remembers the time of the event that last wrote it and an older event never overwrites a newer one; a
revocation or a status change that arrives before the event that created its row creates what it needs and lets the earlier event fill in
the rest. So a replay from the start of a topic, a redelivery, and the whole history in reverse order all end in the same state
(`AcademicReadModelParityTests` checks all three). One consumer per service (`shared.readmodel.kafka`), group `setu-<service>`, offset
`earliest`, started in the background and retried, three deliveries with backoff then `{topic}.DLQ`; metrics `setu.readmodel.events`.

The initial content of each copy is taken from the owning schemas by `V81`. That works only while there is one database; once a service has
its own (6B.2), it becomes the snapshot export of `infra/db/split/split-service.sh` plus the replay, see [data-ownership.md](data-ownership.md) section 4.

## 3. Known differences from the monolith

- **A revoked guardian grant takes effect when its event is applied** (seconds) in the local copy, not in the same transaction. Academic itself
  checks in process, with no delay, and every other service now confirms a guardian's "yes" with Academic before honouring it (6B.2, timeout,
  circuit breaker and bulkhead, refused rather than guessed when Academic cannot answer; see [data-ownership.md](data-ownership.md) section 3), so
  for grants the rule is absolute wherever `setu.guardian-verify.url` is set. A role change keeps the event-lag bound; logouts and suspensions
  do not, because they travel on the revocation list.
- **A completed enrollment is no longer active.** When its final grade is published the student leaves the course for every service that asks "is this student actively enrolled" (course content in Learning, attendance sessions): they keep their grades and transcript, **and read access to the course** (lessons, resources, assignments in Learning, with a notice on the page that it is complete); they cannot scan attendance, submit work or record progress. A student who dropped has neither. Grading is the exception, through `gradableEnrollmentId`.
- **An enrollment is known once it has been ENROLLED.** Requests that were refused or are still waiting never appear in the copy; nothing asks
  about those.
- **Events carry no history.** Rows that existed before the events did come from `V81`; after that, only events change them.

## 4. What stays in the core, honestly

- **Record** is still in the monolith, but no longer reads Academic directly. Everything it needs from Academic (a student's enrollments with
  their course, the student's curriculum, the credits that curriculum requires) comes through one shared port, `RecordDirectory`, answered in the
  monolith by `enrollment.application.RecordDirectoryAdapter`, and who owns a student profile through `AcademicDirectory`; it imports none of
  Academic's classes (three module edges and four class-level dependencies left `module-contracts.txt`; `RecordDirectoryTests`). What is still
  missing before it can run inside the Assessment service: a read-model copy of those facts and the Academic events that keep it current
  (enrollment completion, with the final grade, is now emitted as `EnrollmentCompleted`; courses, curricula and their requirements, and student
  programs are not yet), and a `/record` route to Assessment.
  Until then the core contains the record and reads grades in process: a transitional state, not the target. Likewise **risk**, **AI**, **notification**, **career** and **finance** still call these modules
  in process from the core.
- **Live attendance WebSocket.** The STOMP support (`shared.websocket`) moved out of the notification module so the Attendance service can serve
  its live board itself, with the token check behind a `StompTokenAuthenticator` port (Identity's in the monolith, published keys plus revocation
  list in a service). Clients connect through the gateway, which relays the WebSocket to the Attendance service frame for frame (`WebSocketRelayHandler`,
  [gateway.md](gateway.md) section 3a); it was verified over real sockets in containers, not on this machine.
- **Foreign keys across services**: the 17 that tied Academic, Learning, Assessment and Attendance tables to another service's are dropped by
  `V83` and replaced by write-time checks and a reconciliation count (data-ownership.md section 2). The 17 on tables of modules still in the core
  (record, finance, career, risk) stay until those are extracted; they are frozen in `cross-service-foreign-keys.txt`.

## 5. Running them

Each is inert inside the monolith and starts only with `setu.service=<name>` (set by its `main`). The gateway routes `/learning/**`,
`/assessment/**`, `/attendance/**` and `/staff-attendance/**` to them (`GatewayRouteCoverageTests` fails if any endpoint would reach the wrong
service). Services announce themselves for discovery with `APP_DISCOVERY_ADVERTISE_URL`. Like the others they were first verified through
MockMvc, not over a socket, because embedded Tomcat does not start on the Windows development machine; they now also run as real processes in
containers ([containers.md](containers.md)), which found defects no in-process test could (among them that none of them could start).

## 6. Tests that hold this contract

| Contract | Test |
|---|---|
| The copy answers like Academic for every question, after the college is built and after each kind of change; reverse-order and repeated delivery converge; malformed events are poison | `AcademicReadModelParityTests` |
| Each service starts with only its module and the kernel; decides from its copy; an ended assignment or dropped enrollment stops working on the next request; a revoked login, a forged token and an unknown key are refused | `LearningServiceWiringTests`, `AssessmentServiceWiringTests`, `AttendanceServiceWiringTests` (shared checks in `ExtractedServiceWiringSupport`) |
| The modules behave as before on the port | the unchanged `learning`, `assessment`, `attendance` suites |
| Academic emits each new event in the transaction of the change | `directory`, `enrollment`, `institution`, `catalog`, `guardianaccess` suites and the parity test |
| Eight edges gone, three schemas of tables owned by their services | `ModuleContractFreezeTests`, `TableOwnershipTests` |
| Every endpoint reaches its service through the gateway | `GatewayRouteCoverageTests` |
