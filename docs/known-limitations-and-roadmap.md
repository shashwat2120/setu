# Known limitations and roadmap (Sprint 6C.3)

What is not done, not verified, or different from what the canonical spec or the plan says, stated plainly. Each item says whether it is a
**gap** (the spec asks for it and it is missing), a **limit** (true of this environment or design, with a reason) or a **deviation** (chosen on
purpose, recorded). Nothing here is hidden elsewhere in the docs; the test audit ([non-negotiables-test-audit.md](non-negotiables-test-audit.md))
and the validation report ([validation-report.md](validation-report.md)) carry the evidence.

## Resolved since 6C.3

1. ~~Gap: risk recalculation did not follow the grade event.~~ **Closed.** Spec §16 reads "assessment -> grade event -> risk recalculation"; it used
   to run only when a person pressed **Recalculate**. A risk consumer now recalculates a student's risk on every `GradePublished` (original or
   correction): see [kafka-event-catalog.md](kafka-event-catalog.md) and `RiskEventProcessor`. The consumer's logic and its Kafka envelope handling
   are tested without a broker, with the real outbox payload; consuming from a live broker is the same unverified-here limit as item 2.
   Closing an attendance session is now a trigger too: it recalculates every student actively enrolled in that section, so absences move the score
   without a grade. Still not triggers: submissions, learning progress and `AssessmentCompleted`; adding one is a line in `RiskEventProcessor.TRIGGERS`
   and a test.

## Not verified in this environment

2. ~~**Limit. No live multi-process run.**~~ **Closed.** The services now run as nine containers behind the gateway, with Kafka between them
   ([contracts/containers.md](contracts/containers.md)): the reference flow passes end to end, and running it found nine defects no in-process test
   could, among them that none of the extracted services could start and none of their WebSockets could connect. What stays true on the Windows
   development machine: the test suite is still in-process there, and the broker-backed consumer test skips itself.
3. **Limit. Passkeys were not exercised with a real authenticator.** The ceremony is tested server side (`PasskeyFullFlowTests`); the seeded
   demo accounts have no passkey, so the demo registers one first and needs Windows Hello or a security key.
4. ~~**Limit. The live attendance WebSocket cannot be watched.**~~ **Closed** in containers: the board updates 141 ms after a scan over a real
   WebSocket (`infra/scripts/smoke-websocket.js`). It cannot be watched on the Windows development bridge, which does not upgrade connections.
5. **Limit. Performance numbers are in-process** ([validation-report](validation-report.md)): no network, one machine. The burst of 40 simultaneous
   scans passes at p95 942 ms against a 1 s target, a thin margin; the cached dashboard aggregate is not timed.
6. ~~**Limit. No automated accessibility test** in the frontend.~~ **Closed for what an engine can check** (`npm test` in `frontend/`): the axe-core
   engine runs against WCAG 2.2 AA on every page of the app, for its role, in the state it shows when its data cannot be loaded, plus the sign-in page,
   the check-in pages with data, the shared table and prompt, keyboard order and activation, live-region announcements and the reduced-motion preference
   (`src/test/accessibility.*.test.tsx`). Colour contrast, which needs real layout and paint, was checked in a real browser with the same engine against the running stack:
   **38 pages with live data have no WCAG 2.2 AA violations** (sign-in, landing, 9 student pages including a course page, 10 faculty pages, 17 admin pages), and every
   text and background pair in the design tokens clears 4.5:1 (ink 12.6 to 14.2, soft ink 5.7 to 6.4, button text 5.7, error red 4.9 to 5.6, brass 4.6 to 5.3). **Still not
   covered, and stated so:** the guardian, placement and super-admin pages in a browser (their error and empty states are in the automated suite), pages opened
   through an address with an id (apart from the course page), and use with a real screen reader. The engine marks a few spots per page as undecidable (a fixed top
   bar overlapping the page, a decorative glyph); the colours behind those were computed by hand and pass.

## Not finished

7. **Gap. Four modules are still in the core process:** record, risk, career, finance (and onboarding). The route table sends them to the core.
   Record belongs with Assessment per the coupling audit. It now reads Academic only through the `RecordDirectory` port (first step done); it still
   needs a read-model copy of courses, curricula and student programs, fed by Academic events that are not emitted yet (enrollment completion now is). 17 foreign keys on their
   tables remain and are frozen in `cross-service-foreign-keys.txt` ([extracted-services §4](contracts/extracted-services.md)).
7a. ~~**Gap. Nothing completes an enrollment.**~~ **Closed.** A decision of the college: publishing a final grade completes the enrollment. A
   section has one final-grade component; a grade published for it (`FinalGradePublished`) makes Academic move the enrollment ENROLLED to COMPLETED
   with the grade as `final_status`, and a correction of that grade changes it ([state-machines.md #11](state-machines.md)). Degree progress counts
   passed courses only. **Period close (added 2026-10-20):** an academic period now closes only when every section in it is closed or cancelled and no student in it
   is still ENROLLED, so a close never decides anyone's result: a student leaves ENROLLED by a published final grade or by being dropped. `GET /catalog/academic-periods/{id}/close-check`
   names what blocks a close, `POST .../status` with `closeOpenSections: true` closes the open sections (and cancels never-opened drafts) first, each as the
   ordinary audited change, and a blocked close changes nothing (409). A successful close writes `PeriodClosed` to the outbox. **Still open:** nothing consumes
   `PeriodClosed` yet (Record posting the period result is part of the Record move, item 7), and a section that never gets a final-grade component keeps its
   students ENROLLED until the registrar grades or drops them. Other consequences of the decision: **a completed student is no longer actively enrolled**, so they can no longer take attendance in it, submit work or record progress, but they keep
   reading the course (lessons, resources, assignments, assessments and results) with a notice that it is complete, as well as their grades and transcript; a course can be completed only through Kafka, so on the development machine (slow Kafka client) it can take minutes.
8. **Gap. Only the live attendance board goes through the gateway.** The gateway relays WebSocket upgrades on one route (`/ws`, to Attendance) and
   answers an upgrade anywhere else with 501; Notification's live delivery and the AI service's job status have no route yet, so those clients connect
   to the service directly. The relay is verified over real sockets in containers (not on the development machine) and has no per-frame limits of
   its own.
9. **Gap. Analytics service** (read-model projections) has no code or tables; the coupling audit dropped it as a candidate for now.
   **9b. Limit. Check-in location is a signal, not proof.** A student's device reports where it thinks it is, with the accuracy it believes it has. A phone
   can be made to report any position, and indoors the accuracy can be tens of metres, so a check-in is flagged `FAR` only when it is outside 100 m even
   allowing for that accuracy, and it is never refused. The classroom position is whatever the instructor's device reports when the session opens (optional:
   without it a check-in shows no distance). Only the outcome (status, distance, accuracy) is stored, never the student's coordinates; there is no retention
   rule beyond the record itself, and the consent text is on the check-in form. The instructor sees it per session ("Checked in from"); the student sees their own
   history under **Your recent check-ins** on the Attendance page (`GET /attendance/enrollments/{id}/records`: the student, the section's faculty and an
   administrator; a guardian gets the records without the place). Checked live through the gateway on 2026-10-20: near, far and not shared all appear to the
   instructor, and a second student gets 403 on someone else's history.
   **9c. Staff check-in location (added 2026-10-20), off by default.** A college can switch on recording where its staff check in from (Staff Attendance page:
   campus position and radius, default 300 m). When on, each staff check-in stores only the outcome (near, far, not shared, no campus position), the distance and
   the accuracy the device claimed, never the person's coordinates, and the administrator sees it in the day table. It is information and never a rule: a check-in is
   never refused or changed because of it, a college that has not switched it on has any reported position ignored and nothing stored, staff are told on the
   check-in page before their device is asked, and only an administrator changes the setting or sees the campus coordinates. The same limits as 9b apply (a phone
   can report any position; indoors the accuracy is tens of metres). The admin's student Attendance page now shows the student check-in location too, as the
   instructor's page does. **Not built:** a retention rule for these outcomes beyond the record itself, and any check-out location.
   **9a. Closed, with one limit. Online payment through Razorpay.** A student pays a fee in Razorpay's own checkout window (card, UPI and net banking
   never touch Setu); the server creates the order for the fee's amount, and the payment becomes `SUCCEEDED` only through the provider's signed
   webhook or a server-to-server lookup of the order, never on what the browser says ([state-machines.md #20](state-machines.md)). Verified in tests
   with signed webhooks and a stub provider; what it needs to run against the real provider is the key pair and webhook secret in the git-ignored
   `.env` files (no keys, no online payment: checkout answers 503). **Limit:** Razorpay can only reach a webhook on a public address, so on a laptop the
   confirmation comes from the lookup the page triggers after checkout (and a "Check again" button), and the webhook path is exercised by tests;
   a deployed college registers `/finance/provider-webhooks/razorpay` in Razorpay's dashboard. Only full refunds are applied, only INR-like
   two-decimal currencies are accepted, and a student sees one payment per fee.
10. ~~**Open design question. Assigned-faculty access is not tied to the faculty role.**~~ **Closed 2026-10-20.** The rule is now the one Assessment always had, everywhere:
    a section assignment says which section, never that the person may act as faculty. Attendance (sessions, records, the live board), Learning, the section and
    enrollment lists and the advisor/risk "advisee" check all ask `AuthorizationContext#holdsFacultyRole` as well as the assignment, so an assignment that
    outlived a role change opens nothing (`FacultyRoleRequiredTests`). An administrator still reaches a section through the administrator permissions, not an assignment.
11. **Limit. A revoked guardian grant is applied to the local copy within seconds** (event lag); guardian access is nonetheless confirmed with
    Academic on every use, so for grants the rule is immediate wherever the verifier is configured ([data-ownership §3](contracts/data-ownership.md)).
12. **Limit. Access-token liveness costs two indexed queries per request** and fails closed. Acceptable at this scale; a short per-instance cache
    or the Redis denylist would be the optimisation, not a correctness change.
13. **Limit. Client-supplied `tenantId` in some write bodies** is authorized and covered by per-service cross-tenant tests, but the generic sweep
    covers GET endpoints only.

## Deviations from the written plan (on purpose)

14. **The gateway is a small servlet gateway, not Spring Cloud Gateway**, because no Spring Cloud release train supports Boot 4.1
    ([gateway §1](contracts/gateway.md)). It sits behind the same route table and tests, so swapping it is one class.
15. **No OpenFeign**; Resilience4j core is used directly behind `GuardedCall` ([data-ownership §3](contracts/data-ownership.md)).
16. **Gemini is the primary AI provider** for chat and embeddings, with a second Gemini model, then (when a key is set) a hosted NVIDIA model, and then local Ollama as the last resort. The local
    model is CPU-only on the development machine and slow; it is a fallback, not the demo path. Gemini's free embedding tier is capped at 1,000
    requests a day, so tests embed with the built-in local embedder and one opt-in test (`live-gemini`) checks the real thing.
17. **Seed and example data are marked as such**; none of the demo numbers are real.

## Roadmap

In the order that removes the most risk first.

1. **Add the remaining risk triggers** if wanted (assignment graded, `AssessmentCompleted`; attendance session closed is done), each with its own test.
2. **Done for the layout** ([contracts/containers.md](contracts/containers.md)): the services run as containers and Kafka lag, the Grafana panels and the
   live board were verified live. **Still open:** the DLQ against a live broker with a real poison event, and everything under load (items 2, 5).
3. **Finish moving Record into Assessment** (the port and the completion event are in place): Academic events for courses, curricula and student
   programs, a read-model implementation of `RecordDirectory`, the `/record` route; **then Finance and Career**, each behind its own read-model
   copy, with the frozen foreign keys replaced by write-time checks and a reconciliation count, the same recipe as 6A.5 (item 7). The completion event now exists, so the record's copy has
   the enrollment facts it needs.
4. **Done for the live attendance board** (item 8). **Still open:** a route for Notification's live delivery and the AI job status, each on a path of its own.
5. ~~Settle the faculty role-versus-assignment rule~~ **Done** (item 10).
6. ~~Add a hosted second AI fallback~~ **Built, off until a key is set.** A hosted model on NVIDIA's OpenAI-compatible endpoint (`meta/llama-3.3-70b-instruct` by default; any
   tool-calling model) is tried after the second Gemini model and before the local one, through the same tools and the same per-user authorization. It needs
   `NVIDIA_API_KEY` in the git-ignored `.env` files (`pwsh infra/scripts/set-nvidia-key.ps1` asks for it without showing it); without a key nothing about it exists.
   Scripted tests cover the order (`AiChatNvidiaFallbackTests`) and the on/off wiring; **not yet checked against the real endpoint**.
7. **Done** (item 6): the frontend test runner and its accessibility checks. **Next there:** data states for the remaining pages.
8. **Analytics read models** when a consumer for them exists (item 9).

## Explicitly not planned (canonical spec §15)

Kubernetes, one microservice per table, full event sourcing, unlimited agents, raw model access to the database, a search cluster on day one,
video transcoding, WebRTC classrooms, binary storage in PostgreSQL, a guardian master switch, a semester-only period model, AI deciding
attendance, grades, eligibility or discipline, and any claim of blanket exactly-once delivery.
