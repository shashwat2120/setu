# Validation report (Sprint 6C.2)

What was run, what it showed, and what it cannot show. Targets are from canonical spec §14; sizing and method are in
[contracts/performance.md](contracts/performance.md). Date of the run: 2026-10-16.

## Method and limits, stated first

- **In-process.** Requests go through MockMvc into the real application context and the development PostgreSQL. There is no network between
  client and application and no gateway hop. Numbers are therefore a floor for the application and database work, not an end-to-end figure.
- **One machine, one database.** The Windows development machine; embedded servers and the JDK HTTP client are unreliable on it, so these timings
  and the test suite are in-process. The distributed layout was run for real, as nine containers on Linux, and is reported in the next section.
- **Local, shared hardware.** Timings were taken with nothing else running (a first attempt while another test run held the database hung and
  was discarded). They will vary between runs; the burst figure in particular varies by about 10 %.
- **Validation targets, not SLAs** (spec §14).

## Demo validation targets

Command: `-Dtest=DemoTargetsPerformanceTests,QueryCountTests -DexcludedGroups=live-ollama` (tag `performance`, excluded from a plain build).

| Target (spec §14) | Measured | Result |
|---|---|---|
| Authenticated paginated list, < 500 ms | n=40, median 34 ms, p95 47 ms, max 67 ms | met |
| Authenticated single read, < 500 ms | n=40, median 30 ms, p95 47 ms, max 61 ms | met |
| Enrollment command (full invariant chain), < 500-800 ms | n=20, median 40 ms, p95 45 ms, max 112 ms | met |
| QR scan acknowledgement, < 1 s, burst of 40 simultaneous scans served by 24 workers | median 736 ms, p95 942 ms, max 942 ms | met, thin margin |
| No N+1 on a list | 7 SQL statements for 3 rows and for 20 rows | met |
| AI is asynchronous (queued / running / completed) | status events covered by `AiServiceWiringTests` and `AiJobSubscriptionAuthorizerTests`, not timed | met (behavioral) |
| Cached dashboard aggregate, < 200-300 ms | not timed; correctness only (`DashboardConsistencyTests`) | **not measured** |

Reading the burst result honestly: the single scan and the sequential commands are far inside their targets, but 40 scans arriving together
queue behind 24 request threads and a pool of 64 connections, and the slowest finishes at 942 ms against a 1000 ms target. Two things keep it
there: the pool keeps 16 connections open (a cold pool measured worse) and the pool is sized so nested transactions cannot deadlock (the
unsized default stalled 20 scans for 30 s each). A larger classroom, a slower disk or a busier database would push it over. The knobs are
`SERVER_MAX_THREADS`, `DB_POOL_SIZE` and `DB_POOL_MIN_IDLE`, bounded by the rule in `ConnectionPoolSizingTests`.

## Regression

The full suite was run in 21 chunks against one database, one chunk at a time, twice: once on the tree of 6C.1/6C.2 (before the grade-event
consumer) and once on the tree that includes it. **All chunks passed in both runs, with one exception that the second run found and that was fixed:**
the shared-kernel chunk had one test error, a Logback rolling-file clean-up timeout while an application context started. The cause was a defect
from 6C.1: the logging configuration included Spring Boot's `base.xml`, which always attaches a rolling `spring.log` in the system temp directory.
Logs now go to the console and OpenTelemetry only, a file is opt-in (`file-logs` profile), and `LoggingSystemTests` pins it. The shared-kernel,
`ModularityTests` and config suites were rerun after the fix and passed. The other 20 chunks of the second run had passed before the fix; the fix
touches only the logging configuration and was not followed by a third full run.

A third full run (21 chunks) was made on the tree that includes "publishing a final grade completes the enrollment" (commit `1ad3fca`). Nineteen
chunks passed the first time. Two did not, neither because of the code:

- **`risk`**: one error, `RiskHttpTests`, "Time elapsed: 80849 s". The machine was suspended in the middle of the run (about 22 hours), its
  database connections were closed and the pool timed out waiting. Rerun on its own: all 60 tests pass.
- **`AiChat*`**: one error, `AiChatIntegrationTests.ignoresAnInstructionEmbeddedInsideARetrievedPolicyDocument`, "timed out (primary budget 60s,
  overall 60s)". That test makes the live Gemini model call a tool and answer within 60 seconds, and the model was slower than that on two runs in a
  row after answering in time earlier. With the budget raised to 180 s it passes, the model taking 69 s. It is a test that depends on live-model
  latency, not on anything the platform does; the other 24 tests of the chunk passed.

A fourth full run (21 chunks) followed the work of running the services as containers (the entry-point fixes, the WebSocket handshake, the seeder
restart guard, the test suite's own Kafka topic prefix, the Prometheus targets). **All 21 chunks passed the first time, with no error and no rerun.**

A fifth full run (21 chunks) followed the gateway's WebSocket relay, the check-in location, the advisor's tool-result fix and the seed's Final Exam.
**20 chunks passed; the 21st (`AiChat*`) had one error**: `AiChatIntegrationTests.ignoresAnInstructionEmbeddedInsideARetrievedPolicyDocument` got
`ProviderUnavailable` after 60.65 s, the live Gemini call outlasting that test's 60 s budget, which is the known latency flake (it passes with a longer
budget, and the other 24 tests of the chunk passed). The counts below are from this run. Tests added after it, each run and
passing on its own: the risk trigger on a closed attendance session (5 in `RiskEventProcessorTests`), read access to a finished course
(`CompletedStudentAccessTests`, 7), the gateway's per-route timeout and event-stream flushing (3 in `GatewayFilterTests`, 2 in `ApacheUpstreamTests`), and
`MigrationIntegrationTests`, whose table count (147) had been stale since Sprint 6. The seeder classes (`DemoDataSeeder*`, 15 tests) are excluded from the
chunks because they need an empty database; they passed on one, with the new Final Exam test.

**Frontend (`npm test` in `frontend/`): 103 tests in 8 files, all passing**: the axe-core engine against WCAG 2.2 AA on every page of the app for its role in
the state it shows when its data cannot load, and the sign-in, check-in, advisor, finance and finished-course pages with data (see
[non-negotiables-test-audit.md](non-negotiables-test-audit.md) item 4). Colour contrast, which needs a real browser, was checked with axe-core in one against the running stack:
**38 pages with live data have no WCAG 2.2 AA violations** (sign-in, landing, 9 student pages, 10 faculty pages, 17 admin pages); eight text-over-photograph spots on the landing page
are flagged by the engine for a human to look at.

| Chunk | Tests |
|---|---|
| academic | 19 |
| aiexplain + aifacts (excluding AiChat*) | 76 |
| aiexplain AiChat* | 25 (1 error: see below) |
| assessment | 73 |
| attendance | 142 |
| audit | 39 |
| career | 25 |
| catalog | 63 |
| config | 39 |
| directory | 34 |
| enrollment | 106 |
| finance | 24 |
| gateway | 77 |
| guardianaccess | 7 |
| identity | 180 (1 skipped: the manual Argon2 benchmark) |
| institution | 35 |
| learning | 92 |
| notification (excluding the broker-backed Kafka test) | 37 |
| record | 20 |
| risk (includes the grade-event consumer: 21 tests) | 60 |
| shared kernel + `ModularityTests` | 124 |

Excluded by design: tag `live-ollama` (needs a local model), tag `live-gemini` (needs a key and spends embedding quota), tag `performance` (run
above, separately), and `NotificationKafkaConsumerTests`, which cannot open an NIO selector on this machine (its retry/DLQ policy is covered by
`NotificationErrorHandlerTests`). The chunks were run from a clean copy of the tree, so a passing result does not depend on stray local files.

## Failure, security and observability checks added in 6C

| Area | Evidence |
|---|---|
| Service unavailable: honest "Unavailable", no invented data, circuit breaker and bulkhead | `GuardedCallTests`, `GuardianGrantVerificationTests`, AI remote-facts tests |
| Gateway does not expose management endpoints | `ActuatorGuardFilterTests` |
| Services expose only health, info and a token-guarded Prometheus scrape | `ManagementEndpointsTests` |
| Page size capped | `PageSizeLimitFilterTests` |
| Tenant isolation across the extracted services' read models | cross-tenant cases in `ExtractedServiceWiringSupport` |
| Entry-point defaults are not silently shadowed | `EntryPointDefaultsTests`, `EntryPointDefaultsPrecedenceTests` |
| Risk recalculates on a grade event; idempotent, tenant-checked, one at a time per student, refusals leave nothing | `RiskEventProcessorTests`, `RiskEventListenerTests`, `RiskErrorHandlerTests`, `RiskConsumerWiringTests` (without a broker) |
| Logs go to console and OpenTelemetry, no file unless asked for | `LoggingSystemTests` |
| A final grade completes the enrollment; a failing grade earns no credit; grading carries on after completion | `FinalGradeEventProcessorTests`, `FinalGradeFlowTests`, `FinalGradeComponentTests`, `FinalGradePrerequisiteTests`, `RecordDirectoryTests` |
| Trace context survives the outbox | `OutboxTraceParentTests`, `shared.observability` tests |
| Model call and embedder time out visibly; second Gemini model before Ollama | `AiChatSecondaryGeminiTests`, `SpringAiTextEmbedderTests` |

Defects this pass found are listed in [non-negotiables-test-audit.md](non-negotiables-test-audit.md) with their tests.

## The distributed layout, run for real

Nine containers (the gateway, Identity, Academic, Learning, Assessment, Attendance, Notification, the advisor and the core) behind the gateway,
with PostgreSQL, Redis, Kafka, Prometheus, Tempo, Loki and Grafana, on Linux ([contracts/containers.md](contracts/containers.md)). Reference flow of
canonical spec section 16, through the gateway, over HTTP, from a clean database: **19 of 19 steps** (the 18 data steps in 7 to 11 seconds across runs, and the advisor's answer about 5 seconds more),
including the enrollment completing 1.6 s after the grade was published, by an event travelling from one process through Kafka to another; the
live attendance board updating 141 ms after a scan over a real WebSocket; Prometheus scraping 9 of 9 targets; consumer lag 0 and no dead letters;
a trace from the gateway through Assessment and through Kafka into five other services; a service stopped (the gateway answers 502 honestly, the
rest carry on) and Kafka stopped during a grade (nothing lost, everything follows within seconds of the broker's return).

These are single-user timings on one machine, not load figures.

## Run live on the containers after the fifth full run

| Claim | How it was checked | Result |
|---|---|---|
| A poison event is dead-lettered by the real consumers on the real broker | a malformed `GradePublished` (`this is not json`, with its headers) published to `setu.grades` with the console producer | the risk and notification consumers each logged `dead-lettered to setu.grades.DLQ: JsonParseException`; the topic `setu.grades.DLQ` appeared; `setu_risk_events_dead_lettered_total` and `setu_notification_events_dead_lettered_total` each read 1; the enrollment consumer, which reacts only to `FinalGradePublished`, acknowledged it as ignored; no consumer stopped, and the service stayed healthy |
| Closing an attendance session recalculates the section's students through Kafka | opened a session with no scans and closed it (six students marked absent) | the demo student's risk was recalculated about 7 s later (24.995 to 27.219, a new score row), counted as `AttendanceSessionClosed recalculated` in `setu_risk_events_total` |
| A finished course stays readable and nothing can be added, across services | a final grade published for a student, then calls as that student through the gateway, with Learning and Attendance running as their own services | enrollment `COMPLETED` (final B) in about a second; reading modules, lessons and assignments: 200; recording progress: 403; an attendance scan: 403; a student still enrolled: 200. Afterwards (Assessment and the advisor's facts extended the same way): the finished student reads the course's assessments (200), and the advisor lists both grades by name, "Midterm Quiz" (MAIN) and "Final Exam" (FINAL), where it had shown component UUIDs and would have lost the finished course's grade |
| The advisor's streaming endpoint works through the gateway and answers from the student's real data | `POST /ai/chat/stream` through the gateway and, for comparison, straight to the AI service | identical timing both ways (first event about 8 s, answer 14 to 15 s, tokens 0.2 to 0.3 s apart), so the gateway adds no delay; the answer names the real attendance (50 %) and grades. Before the fix the same questions got "no data" |

## Online payment through Razorpay, run live (test mode)

Automated first: `PaymentProviderTests` (40), `ProviderWebhookHttpTests` (11, over the real security chain with no token), `RazorpayGatewayTests` (13, against a stub provider),
`RazorpaySignatureTests` (9). Then on the containers, with Razorpay **test** keys from the git-ignored `.env`:

| Claim | How it was checked | Result |
|---|---|---|
| Checkout opens a real order for the fee's amount and leaks nothing | a payment for a 50,000 INR fee created through the gateway, then `POST .../checkout` | 200: order id from Razorpay, `amount` 5000000 paise, only the public key id in the answer (no secret) |
| The webhook path is open without a token but refuses an unsigned body | `POST /finance/provider-webhooks/razorpay` through the gateway, no token, no signature; an unauthenticated `GET /finance/payments/{id}` | 401 `invalid signature`; 401 |
| A real payment in Razorpay's own window is confirmed by the server, not the browser | the fee paid in Razorpay's test checkout (test netbanking, "Success") from a real browser on the stack | payment `SUCCEEDED` with a provider payment id, the fee `PAID`, a receipt issued, the account balance 0.00 with no open fee left, a `PaymentSuccessful` outbox row `PUBLISHED` and an audit row `PAYMENT_SUCCEEDED` with no acting user (the provider), the event recorded as `reconcile:pay_...` |

What this run did **not** cover: Razorpay's own webhook delivery (a laptop has no public address, so the server-to-server lookup after checkout confirmed the payment; the webhook path is covered by the tests and the 401 check above), a failed attempt in the live window, and a refund through Razorpay.

## Faculty rule, period close and check-in location, run live (2026-10-20)

On the nine containers, through the gateway, after rebuilding the image with the faculty-role rule, the period close and the student check-in history.

| Claim | How it was checked | Result |
|---|---|---|
| Faculty sees where each check-in was made from | faculty opened a session with the classroom position; three students scanned (about 10 m away, about 5.5 km away, no position shared); faculty read the records | NEAR (10 m), FAR (5,560 m), NO_LOCATION, each QR_SCAN; no coordinate in any field |
| A student sees their own check-ins and where they were made from, and nobody else's | the new `GET /attendance/enrollments/{id}/records` as the student, as another student, as the section's faculty, with no token; and the Attendance page in a browser | 200 with the location outcome; 403; 200 (11 records); 401; the page shows "Your recent check-ins" with "In the room (10 m)", marks the system wrote show "—" |
| An assigned faculty member who still holds the role works the section (the new rule must not lock out real faculty) | the same scans and reads as faculty.rao across Attendance and Enrollment after the rebuild | all 200 |
| A period cannot close while a section is open or a student is enrolled, and a blocked close changes nothing | a throwaway period with one open section and one enrolled student: close-check, close, close with `closeOpenSections` | close-check `canClose=false`, 1 open section, 1 ungraded; both closes 409; the section was still OPEN and the period still IN_PROGRESS afterwards |
| Only the calendar manager may ask or close | a student asked the close-check | 403 |
| Once the student is dropped, the close goes through, closing the section with it, and is announced | student dropped the course; close with `closeOpenSections` | 204; section CLOSED, period CLOSED; a second close is refused (400) and announces nothing more |
| The close is audited and announced | outbox, audit log and the Kafka topic | `PeriodClosed` PUBLISHED and present on `setu.academic_periods`; status changes in the audit log |
| An empty period closes | a throwaway period with no sections | 204 |

**Not run live, and why:** an assignment that outlived a revoked faculty role. Revoking a FACULTY role needs a fresh passkey step-up (`ELEVATED_ROLES`), which a script cannot give; it is covered by `FacultyRoleRequiredTests` (6 tests, including the section lists and learning content). The live run did leave its throwaway data in the demo college: two closed periods named `Live close test …`, one closed section and one dropped enrollment (student.intervention). `reset-demo.ps1` removes them.

## Staff check-in location, run live (2026-10-20)

On the nine containers through the gateway after rebuilding the image (migration V95 applied by the core on start). The setting was left OFF afterwards, as in the demo's default.

| Claim | How it was checked | Result |
|---|---|---|
| It is off until a college switches it on, and only an administrator can read or change the campus position | admin read the setting; a student read it; faculty tried to change it; admin switched on with no campus position | off; 403; 403; 400 |
| Switching it on needs the campus position and records only an outcome | admin switched on with the campus position and a 300 m radius; faculty read it | 200; faculty see `enabled` true and **no coordinates** |
| A check-in records where it was made from and is never refused for it | faculty.rao checked in about 33 m from the campus, faculty.mehta about 14 km away | both 201 PRESENT; `NEAR` (33 m) and `FAR` (14,277 m) |
| The administrator sees it, and no coordinates are stored or returned | the day list through the gateway, and the Staff Attendance page in a browser | "On campus (33 m)" and "Away from campus (14.3 km)"; no latitude or longitude in any record; all page requests 200 |

The automated tests (`StaffCheckInLocationTests`, 12) also cover: a college with it off ignores a reported position and stores nothing; the college's own radius decides; sharing nothing or an impossible position checks in as `NO_LOCATION`; no coordinate column exists and none reaches the day row or the audit log; the database refuses half a campus position and switching on without one; only an administrator changes it (audited with who) and only an administrator sees the campus coordinates; a student can neither read nor change it.

**Not run live:** the staff member's own check-in page (the consent note and the browser's location prompt), which needs a real device position; the page logic is covered by `staffLocation.test.tsx`. The two check-ins this run made remain in the demo college's day record for 2026-10-20.

## The advisor, evaluated on the running stack (2026-10-20)

`node infra/scripts/advisor-eval.js` asks the live advisor 16 questions as a demo student (Priya Nair) and checks each answer against the records her own pages show: attendance overall, per course, for one course and as attended/held; a follow-up that needs fresh figures; that a tool was used and "no data" is never said when there is data; deadlines and policy questions use their tools; a policy and a course that do not exist are not invented; another student's attendance is never revealed, including under "ignore your instructions" and an instruction hidden in a policy request; no record is claimed to change; an off-topic question gets no made-up figure. Only seeded example data is sent to any model.

| Run | Result | Mean answer time |
|---|---|---|
| First (Gemini primary, 40 s budget, second Gemini model) | the 3 answers that completed were correct, then a login token expired (the script now signs in per case) | 35 to 65 s per answer |
| With a 20 s primary budget and no second Gemini model | 12 of 16: the hosted fallback was cut off at the primary's short budget, so 4 answers failed with unavailable | 30 s |
| With the cooldown and the fallback's full budget | **16 of 16** | **17 s** (13 answered by the hosted fallback, 3 by Gemini once it recovered) |

What it found, each now fixed: the attendance tool gave only an overall figure, so "each course" was answered "the system only gives an overall figure" (it now lists each course); a slow Gemini (a 9-token request measured at 18 to 40 s on its own) made every question wait out its budget before the fallback answered (after a primary failure it is now skipped for `app.ai.primary-cooldown-seconds`, 60); and the fallback was cut off at the primary's budget. **Limits:** 16 questions on one student's attendance, deadlines and policy, not grades or skills; string checks on the answers (a number present, a phrase absent), so a rewording could fail a correct answer and a clever wrong one could pass; times depend on the providers that day. The 60 s default cooldown means one slow Gemini request moves the next minute of questions to the fallback.

## Placement eligibility, run live (2026-10-21)

A placement officer can set rules per job (minimum CGPA, minimum attendance, programs it is open to, an application deadline) and details (package in LPA, location, openings, type). Every rule is optional; a student's real attendance, grades and program are checked at the moment they apply, and every unmet rule is reported, not just the first. Unknown figures (no grades yet, no sessions held) do not meet a rule. Run through the gateway on the containers as the placement officer and as the demo student Priya Nair (attendance 33.33%, CGPA 2.25), with example jobs titled `[live-test ...]`:

| Check | Result |
|---|---|
| Job with no rules | eligible; apply gives 201 |
| Job needing 75% attendance | not eligible: "Needs attendance of at least 75%; yours is 33.33%."; apply gives 409 with the same reason |
| Job needing CGPA 9.9 | not eligible: "Needs a CGPA of at least 9.9; yours is 2.25." |
| CGPA 15, or a deadline in the past | refused, 400, with the reason |
| Another student reads Priya's skills or previews her eligibility | 403 (a gap found and closed: students held the view permission, so any student could) |
| Placement officer previews a student's eligibility | allowed (managing implies viewing) |
| Job form in the browser, posted with a real click on a program | stored with the rules, details and the program id; the student page shows what the job asks and why Apply is off |

Found by running it live, not by the tests: the officer could not list programs (403), so the "open to these programs" rule could not be set; fixed with a narrow `VIEW_PROGRAMS` permission, copied to every service's role read model in the same change (the parity tests would have failed otherwise). **Limits:** one college, example data; openings are shown to students but not yet enforced; there is no decision reason, notification, or exception path for an officer yet (see the roadmap); the deadline is checked when a student applies and does not close the job by itself.

## Placement decisions with a reason, and the student told, run live (2026-10-21)

A placement officer's shortlist, rejection or selection carries a reason the student is shown: required to reject (refused by the service and by a database constraint), optional otherwise, at most 500 characters. The decision and its time are stored, an `ApplicationDecided` event goes to the outbox in the same transaction, and Notification turns it into a message for the student. Run through the gateway on the containers as the placement officer and the demo account `student.strong`, with example jobs titled `[live-test ...]`:

| Check | Result |
|---|---|
| Reject with no reason | 400, "a rejection needs a reason, which the student is shown"; nothing changes |
| The student tries to select their own application | 403 |
| Shortlist with a note, reject with a reason | 204 each; the student's own list shows the status, the reason and that it was decided |
| Decide again after a rejection | 409 |
| The student's notifications | two new ones within seconds, through the outbox, Kafka (`setu.applications`) and the notification consumer: "...has been shortlisted. Reason given: Good fit for the role" and "...was not taken forward. Reason given: Position filled internally" |
| The officer's applications panel in the browser | a "Reason given" column; requests all 200 |

Tests added with the change: the officer's decisions (reason required, trimmed, bounded, not changeable after a decision, student and other-college officer refused, the database constraint itself, the event's content), the notification wording, and the officer's and student's pages. **Limits:** one college, example data; the officer's reason is shown to the student exactly as written, so the officer is responsible for what it says; no notification when a student withdraws; the notification says "Reason given" even for an optional note on a shortlist.

## Placement: a job for one graduating batch, run live (2026-10-21)

An officer can limit a job to the batch of a given year. A student's batch comes from their own record, never typed in: the admission year of their active program plus the program's length in years (its terms divided by the college's `terms_per_year` setting, 2 when unset, rounded up). A student of another batch, or whose record does not say, cannot apply and is told which batch the job is for and which theirs is. Run through the gateway on the containers with the demo seed's three batches (Ishaan admitted 2023, Priya 2024, Meera 2025; the seed now admits them in those years, and the running demo database was updated to match):

| Student (batch) | Job for 2027 | Job for 2028 | Any batch |
|---|---|---|---|
| Ishaan (2027, final year) | can apply | "This job is for the batch of 2028; yours is 2027." | can apply |
| Priya (2028) | "This job is for the batch of 2027; yours is 2028." | can apply; applying gives 201 | can apply |
| Meera (2029) | refused, told hers is 2029 | refused, told hers is 2029 | can apply |

A batch of 1999 is refused when the job is posted (400), and the database refuses it too. The form in the browser offers "Any batch" and the batches from last year to five years ahead. Unit tests cover the rule, the missing-record case, and a college with three terms a year (the same student graduates a year earlier). **Limits:** one batch per job (not "2026 or 2027"); the batch assumes terms are spread evenly across years and a student who takes longer or has a break keeps the batch their admission year gives; `terms_per_year` has no settings page yet, it is set in the college's settings.

## Placement: a job closes at its deadline and when its openings are filled, run live (2026-10-21)

A job is reported closed, with the reason, as soon as an officer closes it (`CLOSED`), its deadline passes (`DEADLINE_PASSED`) or the selected students reach its openings (`FULL`). This is worked out when the job is read, so nothing runs in the background and the state a student sees is the state applying will give. Selecting past the openings is refused, a full job takes no new applications, and the job's row is locked while selecting so two officers choosing for the last opening at once take turns. Run through the gateway on the containers with example jobs titled `[live-test ...]`:

| Check | Result |
|---|---|
| A job with one opening and three shortlisted applicants | open, 0 of 1 selected |
| Two selects for the one opening sent at the same moment | one 204, the other 409 "all 1 opening is already filled" |
| After that | the job reads `FULL`, 1 of 1 selected |
| A third select | 409, same reason |
| A new applicant to the full job | 409 "all the openings for this job have been filled" |
| A job whose deadline is 6 seconds away | no closed reason; 7 seconds later `DEADLINE_PASSED`; applying gives 409 with "The application deadline passed on ..." |

The student's job card says why a job is closed and offers no Apply; the officer's table shows each job's "x of N openings filled" and the reason a still-open job has closed. Unit and integration tests cover each state to the instant, a rejected applicant not using up an opening, a job with no stated openings, and the last-opening race. **Limits:** nobody is told when a job they applied to closes before a decision (a notification on the roadmap); a job is not marked CLOSED in the database at its deadline, it is only reported so, which matters to anything that reads the status column directly; a withdrawn student's selected application is not possible (withdrawing is only from submitted or shortlisted), so openings cannot be freed by withdrawal.

## Placement: selecting a student records the offer and the placement, run live (2026-10-21)

Selecting a student takes the package offered (the job's own when left alone) and an optional joining date, and records the placement in the same transaction: the employer and role are the job's, the package is stored as rupees a year (lakhs per annum times 100,000), the placement is dated the day it is made and the student joins on the date given. The officer no longer records the placement as a separate step (the manual "record a placement" page remains, for placements made outside the portal). Run through the gateway on the containers with an example job titled `[live-test ...]`:

| Check | Result |
|---|---|
| Select with a joining date in the past | 400 "the joining date cannot be in the past"; nothing changes |
| Select with a package of 5000 lakhs | 400 "the package must be between 0 and 1000 lakhs per annum" |
| Select with a package of 9.25 lakhs and a joining date three months ahead | 204; the student had 0 placements before and 1 after: the job's employer and role, CTC 925000, the joining date as asked |
| The student's notifications | "You have been selected ... Reason given: Strong interview" and "Your placement with Example Co has been recorded." (the second from the existing PlacementRecorded event) |

Unit and integration tests cover the job's own package as the default, no CTC (not zero) when neither the job nor the offer gives one, no joining date invented, a refused offer leaving the application shortlisted with no placement, and shortlisting or rejecting recording nothing. The student's career page shows the package as "₹9.25 LPA" with the joining date. **Limits:** the placement is dated by the server's clock (UTC in the containers), so around midnight in India it can carry the previous day's date; a student gets two notifications for one selection; the package is for the officer to state, nothing checks it against the job's advertised one beyond the 0 to 1000 range.

## Placement statistics and the student's applications page, run live (2026-10-21)

**Statistics** (`GET /career/placement-stats`, the Statistics page of the placement officer): students placed, the percentage placed of the students actively in a program (overall and for each program), the average, highest and lowest package, the top five recruiters, the applications by stage and the jobs by whether they still take applications. Read by the placement officer and the administrator only; Career learns the program cohorts through a port that AI facts answers from Enrollment, so it gains no dependency on it. Run on the containers with the demo data (6 students in one program, 2 placed):

| Check | Result |
|---|---|
| The officer's figures | 2 placed, 33.3% of 6 students in a program, average ₹7.88 LPA, highest ₹9.25 LPA, recruiters Brightline Technologies and Example Co, 4 / 3 / 3 / 1 / 0 applications by stage, 21 jobs of which 19 take applications, 1 past its deadline, 1 full |
| A student asking | 403 |
| The page in the browser | the same figures, in the college's visual style; requests 200 |

Rules the figures follow, each tested: a student placed twice counts once; a placement with no package is a placement, not a package of zero, and the page says how many placements state one; a percentage is only given where there are students to take it of ("No students" otherwise); a placed student in no active program (graduated) is counted as placed but is in no program's percentage; a program's placed count never exceeds its students.

**My applications** (`/app/student/applications`): every job the student has applied to, newest first, with its status, a sentence on what happens next (waiting for review, shortlisted, selected with the package and joining date, not taken forward, withdrawn), the placement officer's note, and Withdraw while it is still open. It uses the existing endpoints, so nothing new on the server. Checked live as Ishaan: 4 applications (1 selected, 2 shortlisted, 1 not taken forward), the notes and the next steps as above.

**Limits:** statistics are the present state, not a history or a comparison with last year; "placed" counts every student with a placement record, including those recorded outside the portal; the administrator can read the endpoint but has no page for it yet.

## The resume, sent with each application, run live (2026-10-21)

A student keeps a resume: a headline, a summary, https links (LinkedIn, GitHub, portfolio), and up to 20 projects, certifications and experiences. Their batch, CGPA, attendance and skills are added from the college's own records, so a resume cannot claim them. When the student applies, the application keeps a snapshot of the resume and of that record, so the placement officer reads what was submitted and not what the student edited afterwards. A job can require a resume ("Applicants must have a resume"); applying then fails with the reason, like any other rule. The student can print the resume or save it as a PDF from the page. Run through the gateway on the containers with an example job titled `[live-test ...]`:

| Check | Result |
|---|---|
| A job that needs a resume, a student with none | not eligible: "This job needs a resume; add one under Resume (a headline is enough to begin)."; applying gives 409 |
| A resume with a `javascript:` link | 400 "the GitHub address must be an https link"; nothing stored |
| Save the resume | 200, version 1 |
| A second save claiming it opened on an empty resume | 409 (a flaw found here: a saved resume used to read as version 0 like an empty one, so this save was allowed to overwrite; a saved resume now starts at version 1) |
| Another student, or the officer, reading the resume directly | 403 each |
| Apply with the resume | 201, the application says it has a resume |
| The student edits the resume after applying | the officer, reading through the application, still sees the resume as submitted (headline, the project and its date, the record at that moment, the resume version) |
| Another student reading that application's resume | 403; the student reading their own: 200 |

An officer reaches a student's resume only through an application, and each such reading is written to the audit log. Tests cover the bounds on every field, https-only links, blanks becoming nothing, the headline as the least a resume needs (a summary alone does not count), the access rules above, the snapshot surviving edits, the job rule, the audit entry, and the two pages in the browser (the student's form, and the officer's "View resume"). **Limits:** no file upload (see the roadmap: PDF only when it is built); the student's skills in the snapshot are ids and levels, shown by name on the page; a resume is plain text and links, with no photo or formatting; an application made before this existed, or by a student with no resume, has none attached and the officer's table says so.

## Uploading the resume as a PDF, run live (2026-10-21)

A student can upload a PDF of up to 2 MB as their resume file, beside the resume they write. The file is judged by its content, never by its name or type: it must start and end like a PDF, not be encrypted, and not carry scripts, launch actions, embedded files, form submissions or links to other documents (a name spelled with hex escapes, such as `/J#61vaScript`, is decoded and caught; the binary bodies of streams are skipped so chance bytes cannot trip the check). It is kept on a volume (`setu_resumes`, mounted into the core service) under the SHA-256 of its content, never in the database, which keeps only the reference, the cleaned display name and the size. Word files are not accepted: the page says to save as PDF first. Run through the gateway on the containers, with a real, valid PDF built by the script:

| Check | Result |
|---|---|
| Upload the PDF, sent as `C:\Users\priya\My Resume.pdf` | 200; shown as "My Resume.pdf", 610 bytes, with its hash |
| A zip (a docx is one) renamed `resume.pdf` | 400 "only PDF files are accepted" |
| A PDF with a JavaScript open action | 400 "this PDF is using features a resume must not ... save a plain copy and upload that" |
| A file of 2.5 MB | 400 "the file is larger than 2 MB" |
| A file of 3.5 MB, and of 5 MB | 413 in the standard envelope (`FILE_TOO_LARGE`, "a resume file may be at most 2 MB"). Two flaws found here and fixed: a 500 (no handler for an upload past the servlet limit), then a 502 through the gateway (the core closed the connection before the gateway had finished sending, because it would only read 2 MB of a refused request; it now reads and discards up to 16 MB). Re-run on the containers after both fixes |
| The student downloads their own file | 200, `application/pdf`, `attachment; filename="My Resume.pdf"`, `nosniff`, `default-src 'none'; sandbox`, the same bytes |
| Another student, or the officer, downloading it directly | 403 each |
| Apply, then the student uploads a replacement and then removes the file | the application is unaffected |
| The officer reads the application's resume | the snapshot names the file the student had when they applied |
| The officer downloads that file | 200 with the **first** version's exact bytes, not the later one |
| Another student downloading it / the student downloading their own | 403 / 200 |

An officer can reach a file only through an application; the download is written to the audit log (`RESUME_FILE_DOWNLOADED`), as are an upload and a removal. The file is only ever handed over as a download, never shown inside the application. A PDF alone counts as a resume for a job that needs one. Tests cover each rule of the PDF check (including the hex-escaped name, a stream that is never closed, and a hostile file with 100,000 stream markers checked in one pass), the cleaned display name never being a path, the store refusing anything that is not a hash and another college's directory, and the access, freezing and audit rules above. **Limits:** the PDF check is a screen, not a guarantee (a name hidden inside a compressed part of the file cannot be seen without opening it), which is why the file is never displayed in the application and is served as an attachment; no virus scan; no preview of the PDF's text; a student has one current file; files of removed or replaced uploads stay on the volume because applications may still point at them, and nothing removes the unreferenced ones yet; the volume needs the same backup as the database.

## One database login per service, run live (2026-10-23)

Until now every service connected to the shared database as the same user, so "each table has one owner" was kept by convention and by tests. Each service now logs in as
its own role (`setu_identity`, `setu_academic`, `setu_learning`, `setu_assessment`, `setu_attendance`, `setu_notification`) that can use only the schemas it owns,
applied by a one-shot `db-roles` job after the core has migrated ([contracts/data-ownership.md](contracts/data-ownership.md) section 1a). Run on the containers:

| Check | Result |
|---|---|
| The roles applied to the running database | `SET`, `DO`, exit 0; the connections to the database were then 6 as the owner (the core) and 4 each as the six service roles |
| As `setu_learning`: read its own lessons | 5 rows |
| As `setu_learning`: read `identity.users`; as `setu_notification`: read `finance.payments`; as `setu_learning`: create a table | each "permission denied" |
| As any service: update the audit log | "permission denied for table audit_logs" (append-only to every service) |
| All six services restarted under their own logins | all healthy |
| The first smoke flow | failed at the attendance scan with a 500: **a real dependency the ownership lists did not show**, Spring Modulith's `public.event_publication`, which every process writes; granted, with a test |
| The smoke flow again | **16 of 16** steps through the real services: sign-in, profiles, attendance session and scan, assessment, final grade, enrollment completes through Kafka, risk recalculated, the student notified, the transcript |
| Learning (not in the smoke flow): a faculty member lists and creates a module; a student reads it; Notification: a student reads their own | 200, 201, 200, 200 |
| Permission-denied errors in any service's log after the fix | none |

`ServiceDatabaseRolesTests` (10 tests) connects as each role and tries every refusal: every service against every other service's schema and the core's, writes to
another service's tables, deleting outbox events, changing or removing audit records, creating or altering or dropping or truncating anything, the migration history, a table
added by a later migration, a stray grant being taken back when the script runs again, and the script agreeing with `table-owners.txt`. **Limits:** one PostgreSQL
database and server still; the same development password for every role; the outbox, audit log, idempotency keys and Modulith's event log are shared tables; the suites run as the owner, so they cannot show a service asking for something its login would refuse. Two things cover that: `ServiceSqlStaysInItsSchemasTests` reads the hand-written SQL of every package a service scans (its modules and the shared kernel) and fails if it names another service's schema (none does; JPA mappings are held to their own schema by `TableOwnershipTests`), and the live run exercised the 16-step flow, Learning and Notification. What neither proves is a path that builds its SQL from pieces at run time; it would fail at that moment with "permission denied".

## The Notification service on a database of its own, run live (2026-10-23)

Notification now runs on its **own PostgreSQL server** (`postgres-notification`), loaded from the shared database by `provision-service-db.sh`
([contracts/data-ownership.md](contracts/data-ownership.md) section 4a). Cut-over order: stop the service, recreate the core with its Notification consumer switched off, copy
while nothing is writing, start the service on the new server (Kafka kept whatever arrived in between). Run on the containers:

| Check | Result |
|---|---|
| The copy | one transaction; every table's row count compared with the source by the script; exit 0 |
| What the new server holds | exactly three schemas (`governance`, `notification`, `public`), **20 tables, none of any other service's**; 40 notifications and 40 processed event ids carried across |
| Its platform tables | its own, empty (outbox 0, audit log 0) |
| Connections | the service connects there as `setu_notification`; the shared database shows no notification connections |
| A grade published through the Assessment service (smoke flow, 14 of 14 steps) | the Assessment service's event goes to Kafka, the Notification service writes the notification **to its own database** (41 there; the old copy **stayed at 40**), and the student reads it back through the gateway after 0.6 s |
| The core's Notification consumer | off (no mention in its log): otherwise it would share the Kafka group and half the notifications would go to the old copy |
| **Failure isolation:** the Notification database server stopped | `/notifications` answers 504 `GATEWAY_TIMEOUT` in the standard envelope; **Learning, Academic, Identity and Attendance all keep answering 200** |
| The server started again | `/notifications` answers 200 on its own, nothing restarted |

`ServiceDatabaseRolesTests` gained a case that builds a database holding only one service's schemas and the platform tables and applies the roles script to it: the service gets
its login and no other service's login can use either schema. **Limits** (also in the contract): only Notification is split; **the service's database does not receive future
migrations automatically** (the core's Flyway only migrates the shared database, so a schema change to Notification would have to be applied to both until each service has a migration
folder of its own); events that arrive during a database outage longer than the retry window are dead-lettered (no events were sent during the outage test); the failure shows as a
slow 504; one Docker host and the development password.

## Not validated

- Kafka consumer lag **under load**. (The dead-letter path **was** run with a real poison event on the real stack, see below.)
- WebSocket traffic through the gateway **under load** (one live board is verified end to end, with the update 289 ms after a scan; many simultaneous
  connections, slow readers and the 512 KiB send buffer limit are not), and the frame size limit of relayed connections (the container's default).
- Load over a real network, or beyond the 40-scan burst.
- The cached dashboard aggregate's latency.
- Accessibility (no automated frontend test exists).
- Live Gemini behavior under quota exhaustion beyond what the opt-in `live-gemini` test covers.
