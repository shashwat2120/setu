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
   Still not triggers: attendance, submissions, learning progress and `AssessmentCompleted`; adding one is a line in `RiskEventProcessor.TRIGGERS`
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
6. **Limit. No automated accessibility test** in the frontend (no test runner is set up there); accessibility was checked by hand and with
   browser tooling.

## Not finished

7. **Gap. Four modules are still in the core process:** record, risk, career, finance (and onboarding). The route table sends them to the core.
   Record belongs with Assessment per the coupling audit. It now reads Academic only through the `RecordDirectory` port (first step done); it still
   needs a read-model copy of courses, curricula and student programs, fed by Academic events that are not emitted yet (enrollment completion now is). 17 foreign keys on their
   tables remain and are frozen in `cross-service-foreign-keys.txt` ([extracted-services §4](contracts/extracted-services.md)).
7a. ~~**Gap. Nothing completes an enrollment.**~~ **Closed.** A decision of the college: publishing a final grade completes the enrollment. A
   section has one final-grade component; a grade published for it (`FinalGradePublished`) makes Academic move the enrollment ENROLLED to COMPLETED
   with the grade as `final_status`, and a correction of that grade changes it ([state-machines.md #11](state-machines.md)). Degree progress counts
   passed courses only. Open consequences of the decision: **there is still no period close**, so a course whose section never gets a final-grade
   component never completes; **a completed student is no longer actively enrolled**, so they lose the course page and attendance for it (grades and
   transcript stay); a course can be completed only through Kafka, so on the development machine (slow Kafka client) it can take minutes.
8. **Gap. Only the live attendance board goes through the gateway.** The gateway relays WebSocket upgrades on one route (`/ws`, to Attendance) and
   answers an upgrade anywhere else with 501; Notification's live delivery and the AI service's job status have no route yet, so those clients connect
   to the service directly. The relay is verified over real sockets in containers (not on the development machine) and has no per-frame limits of
   its own.
9. **Gap. Analytics service** (read-model projections) has no code or tables; the coupling audit dropped it as a candidate for now.
   **9a. Gap. No real payment provider is connected** (no Razorpay, Stripe or similar). Finance has the whole lifecycle: fee structures, student
   accounts and charges, payments that start as `INITIATED` (idempotent by a client key) and become `SUCCEEDED`, `FAILED` or `REFUNDED` only through a
   provider event deduplicated by its event id, and receipts; no card number or CVV is ever stored. The "webhook" endpoint stands in for the provider and
   is restricted to a finance administrator, because there is no real provider signature to verify. Connecting a provider means creating its order,
   a checkout on the frontend, and a public webhook that verifies the provider's signature and then calls the same `recordWebhookEvent`.
10. **Open design question. Assigned-faculty access is not tied to the faculty role** in Learning and Attendance, while Assessment requires the
    permission as well ([audit item 9](non-negotiables-test-audit.md)). Assignment is still required everywhere, so this is not a leak, but an
    assignment that outlived a role change would keep access to that section's data.
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
16. **Gemini is the primary AI provider** for chat and embeddings, with a second Gemini model and then local Ollama as the last resort. The local
    model is CPU-only on the development machine and slow; it is a fallback, not the demo path. Gemini's free embedding tier is capped at 1,000
    requests a day, so tests embed with the built-in local embedder and one opt-in test (`live-gemini`) checks the real thing.
17. **Seed and example data are marked as such**; none of the demo numbers are real.

## Roadmap

In the order that removes the most risk first.

1. **Add the other risk triggers** if wanted (attendance session closed, assignment graded, `AssessmentCompleted`), each with its own test.
2. **Done for the layout** ([contracts/containers.md](contracts/containers.md)): the services run as containers and Kafka lag, the Grafana panels and the
   live board were verified live. **Still open:** the DLQ against a live broker with a real poison event, and everything under load (items 2, 5).
3. **Finish moving Record into Assessment** (the port and the completion event are in place): Academic events for courses, curricula and student
   programs, a read-model implementation of `RecordDirectory`, the `/record` route; **then Finance and Career**, each behind its own read-model
   copy, with the frozen foreign keys replaced by write-time checks and a reconciliation count, the same recipe as 6A.5 (item 7). The completion event now exists, so the record's copy has
   the enrollment facts it needs.
4. **Done for the live attendance board** (item 8). **Still open:** a route for Notification's live delivery and the AI job status, each on a path of its own.
5. **Settle the faculty role-versus-assignment rule** across Learning, Attendance and Assessment and add the test that asserts it (item 10).
6. **Add a hosted second AI fallback** (for example an NVIDIA-hosted Llama through its OpenAI-compatible endpoint) ahead of the CPU model if
   the local fallback is expected to carry real traffic; this needs the provider and model to be named.
7. **Frontend test runner with automated accessibility checks** (item 6).
8. **Analytics read models** when a consumer for them exists (item 9).

## Explicitly not planned (canonical spec §15)

Kubernetes, one microservice per table, full event sourcing, unlimited agents, raw model access to the database, a search cluster on day one,
video transcoding, WebRTC classrooms, binary storage in PostgreSQL, a guardian master switch, a semester-only period model, AI deciding
attendance, grades, eligibility or discipline, and any claim of blanket exactly-once delivery.
