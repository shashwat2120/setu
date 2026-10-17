# Setu — Demo Script

A walkthrough of what is actually built and running today, using the real
seed data `DemoDataSeeder` produces. Every step below is something a
reviewer can click through themselves against a running `LiveDevBridge` +
Vite dev server — nothing here is aspirational or simulated. Where the
canonical spec's §16 reference flow calls for something not yet built, it
is called out explicitly in **Known gaps**, not silently skipped.

All seeded accounts share one password: `SetuDemo#2025!`.

## 0. Setup

```
cd infra/docker && docker compose up -d
cd backend && $env:DB_PORT="5433"; $env:REDIS_PORT="6380"; $env:KAFKA_BOOTSTRAP_SERVERS="localhost:9093"
  ./mvnw.cmd -q test "-Dtest=LiveDevBridge#serve" -DfailIfNoTests=false
cd frontend && npm run dev
```

Wait for the bridge log to show `LISTENING` on port 8089, then open
`http://localhost:5180`.

The backend loads ignored `backend/.env.local` automatically. Set
`GEMINI_API_KEY` there and the demo defaults to `google-genai` with
`GEMINI_MODEL` to a model that answers for your key (the code default, `gemini-3.8-flash`, accepted requests but never
answered on the key used while building this, so every advisor call waited out its timeout; `gemini-3.5-flash-lite` answers in
about 2-3 seconds, see [backend/README.md](../backend/README.md#ai-advisor)). Gemini is the primary provider everywhere (tests included); with no key the run starts on the local Ollama model and logs a
warning, and if Gemini fails mid-demo a second Gemini model and then Ollama answer instead.

## 1. Password login → role-scoped dashboard

Log in as `admin.sit@demo.setu.local` (College Admin, Setu Institute of
Technology). Lands on the registrar dashboard: real counts for students,
faculty, courses, and programs — pulled live, not hard-coded.

**Passkey sign-in:** the login page now has **Sign in with a passkey** (WebAuthn), and a signed-in user can register
a passkey from **Security** (`/app/security`). The seeded demo accounts have no passkey yet, so to show the canonical
flow: sign in with the password, open Security → register a passkey (Windows Hello / security key), sign out, then use
**Sign in with a passkey**. This needs a real authenticator on the demo machine; it was not exercised end to end in the
automated browser here (the server side is covered by the ceremony tests).

**Rate limiting:** after 10 attempts in a minute for the same email the login returns 429 with `Retry-After`
(per email and per IP; asking for a reset code: 3 per 15 minutes). The attempt is audited once as `LOGIN_RATE_LIMITED`.

**Forgot password (emailed code):** on the sign-in page choose *Forgot password*, enter an account email and press **Send code**,
then **Enter the code** and type the 6-digit code plus a new password. The code expires after 15 minutes, works once, and stops
working after 5 wrong tries; **Send a new code** cancels the older one. Seeded `@demo.setu.local` addresses cannot receive mail,
so for a demo either use an account whose address you control with Gmail SMTP configured, or start the backend with
`EMAIL_PREVIEW_ENABLED=true` and read the code from the file written to `backend/target/demo-email-outbox`. A successful reset
signs out every other session of that account.

**New accounts get a code at once.** Admitting a student, or onboarding a faculty member, creates an account with no password and
immediately emails that person the same kind of 6-digit code, as their "set your first password" email. So a freshly admitted
address already has one valid code before anyone presses *Forgot password*. Asking for another code then cancels the first, so
always use the **newest** email: an older code is refused with the usual "wrong or has expired" message. To demo this: as
`admin.sit@demo.setu.local` admit a student with an address you can read, open the email, and use that code on the reset page to set
the first password (do not press *Send code* first).

## 2. Live QR attendance

Switch to `faculty.rao@demo.setu.local`. Attendance → select a section →
**Open attendance session**. A rotating QR scan token appears with a real
expiry. In a second tab, log in as `student.strong@demo.setu.local`, scan
that token (the student attendance-scan flow), and watch the faculty
session's record count.

**Known gap:** the backend genuinely broadcasts a live update over STOMP
(`/topic/attendance/section/{id}`, tested 9/9 in
`AttendanceStompAuthorizationTests`), and the frontend has a real
`@stomp/stompjs` client wired to it (`useAttendanceLiveUpdates`). It cannot
be watched live in *this* dev setup, because `LiveDevBridge` — the
workaround for a Windows/JDK Selector bug that blocks a normally-run
backend on this machine — is a plain HTTP/1.1 bridge with no WebSocket
upgrade handling (documented in its own source). The faculty page shows
this honestly ("Realtime updates are unavailable right now") with a manual
refresh button, rather than faking a live update. On any normally-run
backend, the exact same frontend code would show live updates with no
changes.

## 2b. Faculty (staff) attendance

Faculty have their own daily attendance, separate from marking students. As `faculty.rao@demo.setu.local` open
**My Attendance** → **Check in**, later **Check out** (the date is the college's own day, taken from the server). A second
check-in or check-out is refused. As `admin.sit@demo.setu.local` open **Staff Attendance**: every faculty member's status for
the chosen day, with **Mark / Correct** (status + required reason). Each action is audited (`FACULTY_CHECKED_IN`,
`FACULTY_CHECKED_OUT`, `FACULTY_ATTENDANCE_MARKED/CORRECTED`). Faculty cannot see each other's records or edit any day; admins
of another college get 403. **Clock and policy.** The page is a clock-in widget: a live clock in the college's timezone (taken from the server, not the browser), one
button that flips between **Check in** and **Check out**, a running "time since you checked in" and total hours today. The **Attendance
policy** button explains the rules in plain language. Default rules: shift 9:00 AM-5:00 PM, 15 minutes' grace, 8 hours for a full day, 4
hours for a half day. Checking in more than the grace period after the shift start shows **Late by Xh Ym**; checking out before a full day is
recorded as a **half day** (and under half a day also **Short hours**). As the admin, **Staff Attendance -> Attendance policy** edits the
rules; each save is a new audited version, and days already recorded keep the version they were judged under (no history is re-judged).
A past day with a check-in and no check-out shows **No check-out** and the faculty member is prompted to request a correction.

The seed data includes example staff attendance for the previous eight weekdays (clearly worded as seed data in each record's
reason: a leave day for Rao, a half day and an absence for Mehta); **today is left unrecorded** so the live check-in works. Each
change also publishes an event to `setu.faculty_attendance` — watch it with `kafka-demo.ps1 follow` (step 9).

**Forgot to check in? Regularisation.** As `faculty.mehta@demo.setu.local` open **My Attendance**: under *My requests* there is a
pending example request (seed data) for a day recorded as absent — "forgot to check in; I was teaching". As
`admin.sit@demo.setu.local`, **Staff Attendance** shows it in *Requests to correct a day*: **Approve** (optional note) applies
09:30–17:00 to that day in the same transaction and audits it; **Reject** needs a note. A faculty member can submit their own
request from the form (past days only, last 30 days, one open request per day) and **Withdraw** it while pending. Nobody can
decide their own request, faculty cannot see or decide others', and admins of another college get 403. Events:
`FacultyRegularisationRequested/Approved/Rejected/Withdrawn` plus the usual `FacultyAttendanceMarked/Corrected` on approval.

## 3. Assessment → grade → risk recalculation

Still as faculty: Assessments → an assessment for the section → record a
grade for a component. Publishing it writes `GradePublished`; with the broker up, the risk consumer picks it up and the student's risk is
recalculated within seconds, with no one pressing anything (the audit entry for that calculation has no actor and names the event). **Recalculate**
stays as the manual path and is the fallback while the Kafka client is still starting (see step 9). Log out, log in as
`student.assessment.risk@demo.setu.local`, check Grades — the new grade is
there, versioned (a correction would add a new version, never overwrite).

Log in as `admin.sit@demo.setu.local` (or faculty), open Student Success —
risk scores are a real, deterministic calculation (feature snapshot hash,
rule version, and calculation time are all stored, per canonical spec §13's
reproducibility requirement) run against attendance trend, assessment
trend, and learning-progress completion. The seeded students deliberately
span LOW/MEDIUM/HIGH bands with real, differentiated underlying data — not
placeholder numbers.

**Taking a quiz, end to end.** Log in as `student.average@demo.setu.local`, open **My Courses → CS101**, and under *Assessments* start the
**Midterm Quiz**. It has three real questions: a multiple-choice one (the options are in the question text; answer with the letter),
a true/false pair of buttons, and a short answer. Save each answer, then **Submit attempt**. Log in as `faculty.rao@demo.setu.local`,
open **Assessments**, choose the section, and press **Attempts** on the quiz: the submission shows the student's name and an **Evaluate**
button. Evaluating auto-grades every question that has an answer key and shows the score (for example "Score 70: 3 questions graded
automatically"); the student then sees "EVALUATED — score 70". Answer keys are never sent to students.

**Registering for a section.** `student.strong` is already in both seeded sections, so *Register for a section* is empty for them. Log in as
`student.learning.risk@demo.setu.local` instead: CS201-A is offered with an **Enroll** button, and enrolling runs the full chain of checks
("Enrollment confirmed").

## 4. Advisor alert → intervention

As faculty or admin, Student Success → a MEDIUM/HIGH student → a proposed
**Recommendation** → Accept. This opens a real `Intervention` row
(PLANNED → IN_PROGRESS → COMPLETED/CANCELLED, a genuine state machine).

## 5. AI evidence-grounded explanation

Log in as a student, e.g. `student.strong@demo.setu.local`. AI Advisor tab
→ ask "how am I doing this term?" The answer comes from a real Spring AI +
Gemini round trip (local Ollama `llama3.2:3b` only as the last-resort fallback) through the application-owned tool set
(`getStudentAttendance`, `getAcademicProgress`, `getAssessmentResults`,
etc.), each tool scoped to self via `AiAccessSupport`, with the exact input
payload logged. It is not a canned response — a different question gets a
different, model-generated answer grounded in that student's real data.

**Known gap (fallback only):** the local 3B model is genuinely small and occasionally
mis-parses a tool call (e.g. claiming a required parameter is missing when
the tool description says it can be omitted for self). That is a real,
disclosed model-quality limitation — the frontend sends only `{message}`
and displays whatever the backend returns verbatim; it never edits or
fakes the model's answer.

## 6. Guardian delegated access

Log in as `guardian.kapoor@demo.setu.local`. The dashboard lists real
access grants, not the relationship alone — Ishaan Kapoor's card is ACTIVE
with the full permission set; a second card for a different student shows
EXPIRED with only `VIEW_GRADES`, proving the UI (and the backend behind
it) never trusts a relationship by itself. Click into the active grant —
every section shown (finance, schedule, attendance, grades, risk) is
scoped exactly to that grant's permission list, nothing more.

## 7. Super Admin platform view

Log in as `super.admin@demo.setu.local`. Colleges → provision a new
college live. Audit Log → the college-creation event appears immediately
(append-only, real `AuditLogWriter` rows). Roles → attempt a role
assignment: it is correctly denied with an honest explanation, because
that action requires a step-up passkey re-authentication this demo
environment's password-only accounts cannot complete — the same disclosed
limitation as step 1, shown transparently rather than bypassed.

## 8. Placement

Log in as `placement.officer@demo.setu.local`. Post a job, add a required
skill, review a real application (shortlist/select/reject), record a
placement. Every list is tenant-scoped and permission-checked
(`MANAGE_CAREER_LISTINGS`), not filtered client-side.

## 9. Kafka: transactional outbox → broker

Every business change that matters (grade published, attendance recorded, risk recalculated, payment, placement,
intervention, student admitted) writes an outbox row **in the same database transaction**; `OutboxPublisher` then
publishes it to `setu.<aggregate>` and only marks the row `PUBLISHED` after the broker acknowledges. This is shown
with the in-container tooling, so it does not depend on the host JVM:

```
.\infra\scripts\kafka-demo.ps1 status         # outbox NEW/PUBLISHED + message count per topic
.\infra\scripts\kafka-demo.ps1 follow         # in a second window: live stream of every setu.* event
```

Then do step 2 (scan attendance) or step 3 (publish a grade) and watch the event appear within ~2 seconds, with its
headers (`event_id`, `event_type`, `event_version`, `correlation_id`, `tenant_id`). `.\infra\scripts\kafka-demo.ps1 tail grades`
prints the last events on one topic.

**Consumers, stated plainly:** three kinds exist. The notification consumer turns grade published, payments, placement, recommendation,
intervention and admission events into rows in `notification.notifications` (see `docs/kafka-event-catalog.md`); the bell in the app header lists
them for the person they belong to (`GET /notifications`). The risk consumer turns `GradePublished` into a recalculation of that student's risk (`setu.risk.events`). Every extracted service also runs a read-model consumer that keeps its copy of identity
and academic facts current ([extracted-services](contracts/extracted-services.md)). Both retry three times, then dead-letter to `{topic}.DLQ`.
An Analytics consumer does not exist.

**Startup delay on this dev machine:** a Windows JDK NIO defect ("Unable to establish loopback connection") stops any
Java Kafka client from starting for a while after the bridge launches (reproducible with a 5-line
`Selector.open()` program, so it is not Setu code or Kafka config). Observed 3 and 5.4 minutes before the bridge's producer
started working; a separate fresh JVM was still failing after 10 minutes, so the delay is **not** simply process age and
cannot be predicted. The outbox keeps retrying every 2 seconds and
nothing is lost; `kafka-demo.ps1 status` shows `NEW` rows draining to `PUBLISHED` once it clears. Start the bridge as
early as possible and run `status` before presenting; if rows are still `NEW`, demo with the broker contents already
published (`tail`) and say so.

## What this demo does not show (honest gaps, not oversights)

The full list, with reasons and the roadmap, is [known-limitations-and-roadmap.md](known-limitations-and-roadmap.md); the stage-by-stage view of
the canonical §16 flow is [final-demo.md](final-demo.md). In short:

- **The distributed layout is not run live.** This script drives the single process on the development bridge. The extracted services
  (Identity, Academic, Learning, Assessment, Attendance, Notification, AI, behind the gateway) are verified through wiring, contract and failure
  tests; see [architecture-walkthrough.md](architecture-walkthrough.md). A multi-process run is not possible on this Windows machine.
- **Grafana, Tempo and Loki exist** (`docker compose up`, http://localhost:3001) and the dashboards are checked against the code, but were not
  watched against a live multi-service scrape.
- **Passkey login** needs a real authenticator; see step 1.
- **Live WebSocket updates** cannot be watched through the bridge; see step 2.

## Anticipated reviewer questions

**"Why can't I watch the attendance count update live right now?"**
Because the dev bridge this environment runs against can't do WebSocket
upgrades — see step 2. Point them at `AttendanceStompAuthorizationTests`
(9/9 passing) and `useAttendanceLiveUpdates.ts` as proof the real
client/server contract exists and is tested independently.

**"Why did role assignment just get denied?"**
Step-up re-authentication is a real, intentional security requirement
(canonical spec's non-negotiables) — not a bug. The demo's seed accounts
have no registered passkey, so they cannot complete it. A real user with a
registered passkey would succeed.

**"Is the AI actually calling a model, or is that canned?"**
A real model call: Gemini first, a second Gemini model next, local Ollama as the last resort. Ask it something unusual and the
phrasing changes every time, unlike a canned response. If a provider fails the next one answers, or the advisor says it is unavailable; it never
invents an answer, and a small local model's occasional tool-call mistake is shown verbatim, never smoothed over.

**"How do I know the risk score isn't just a random number?"**
Every `RiskScore` row stores its rule version, a feature-snapshot hash, and
calculation time — the same inputs reproduce the same score deterministically. `docs/state-machines.md` and the risk module's own tests
cover this.

## Lesson content (real videos and documents)

Seeded CS101 and CS201 lessons use real, publicly available YouTube lectures (for example Harvard CS50P) with real documentation attached as links: course notes, the official Python tutorial and open textbooks. As faculty (`faculty.rao`) open **Courses**, choose a section, open a module, and press **Video & documents** on a lesson to see the link list and attach another. As a student, open the course and click a lesson title to play the video inside the page and open its documents. Setu stores links only; it never stores the files themselves.
