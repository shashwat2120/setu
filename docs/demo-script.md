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

## 1. Password login → role-scoped dashboard

Log in as `admin.sit@demo.setu.local` (College Admin, Setu Institute of
Technology). Lands on the registrar dashboard: real counts for students,
faculty, courses, and programs — pulled live, not hard-coded.

**Known gap:** the canonical flow specifies passkey login first. Passkey
registration/authentication is fully implemented server-side
(`PasskeyController`, WebAuthn ceremony, ARIA ceremony ID tests) but the
demo seed accounts are password-only, and no frontend passkey-registration
UI exists yet — so this step is password login, honestly, not passkey.

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

## 3. Assessment → grade → risk recalculation

Still as faculty: Assessments → an assessment for the section → record a
grade for a component. Log out, log in as
`student.assessment.risk@demo.setu.local`, check Grades — the new grade is
there, versioned (a correction would add a new version, never overwrite).

Log in as `admin.sit@demo.setu.local` (or faculty), open Student Success —
risk scores are a real, deterministic calculation (feature snapshot hash,
rule version, and calculation time are all stored, per canonical spec §13's
reproducibility requirement) run against attendance trend, assessment
trend, and learning-progress completion. The seeded students deliberately
span LOW/MEDIUM/HIGH bands with real, differentiated underlying data — not
placeholder numbers.

## 4. Advisor alert → intervention

As faculty or admin, Student Success → a MEDIUM/HIGH student → a proposed
**Recommendation** → Accept. This opens a real `Intervention` row
(PLANNED → IN_PROGRESS → COMPLETED/CANCELLED, a genuine state machine).

## 5. AI evidence-grounded explanation

Log in as a student, e.g. `student.strong@demo.setu.local`. AI Advisor tab
→ ask "how am I doing this term?" The answer comes from a real Spring AI +
Ollama (`llama3.2:3b`) round trip through the application-owned tool set
(`getStudentAttendance`, `getAcademicProgress`, `getAssessmentResults`,
etc.), each tool scoped to self via `AiAccessSupport`, with the exact input
payload logged. It is not a canned response — a different question gets a
different, model-generated answer grounded in that student's real data.

**Known gap:** the local 3B model is genuinely small and occasionally
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

## What this demo does **not** attempt (honest gaps, not oversights)

- **Grafana / Prometheus dashboards** — not built. Only `health`, `info`,
  and `modulith` actuator endpoints are exposed
  (`application.yml`'s `management.endpoints.web.exposure.include`); no
  Micrometer Prometheus registry is wired. This is 5F.2 scope that was not
  reached.
- **Sprint 6 distributed architecture walkthrough** — Sprint 6 extraction
  (Identity/Academic/Learning/Assessment/Attendance services, the gateway,
  Kafka contract versioning across services) has not started. Everything
  demoed above runs inside the single Sprint 5 Modulith backend.
- **Passkey login** — see step 1.
- **Live WebSocket demo** — see step 2; real code, unverifiable live only
  in this specific dev environment.

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
Genuinely local Ollama inference — ask it something unusual and the
phrasing changes every time, unlike a canned response. The tool-calling
occasionally fails on the small local model; that failure is shown
verbatim, never smoothed over.

**"How do I know the risk score isn't just a random number?"**
Every `RiskScore` row stores its rule version, a feature-snapshot hash, and
calculation time — the same inputs reproduce the same score deterministically. `docs/state-machines.md` and the risk module's own tests
cover this.
