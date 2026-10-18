# Non-negotiables test audit

Audit of canonical spec §3 (non-negotiables), §5 (schema invariants), §6–§10 (security, realtime, events, Redis, AI) and the §13
test gates, against the automated tests in `backend/src/test`. For each item: the evidence that exists, and what this audit added.
A claim here means a named test passes, not that the code looks right.

Status: **covered** (an automated test fails if it breaks), **added** (this audit wrote the test), **fixed** (the audit found a defect,
fixed it and tested it), **open** (no test yet; stated plainly in "Open items").

## §3 Non-negotiables

| Non-negotiable | Evidence | Status |
|---|---|---|
| Account is separate from the profile | `SchemaInvariantTests.anAccountIsSeparateFromEveryProfile` (no credentials or user link on profile tables, no profile data on the account, person is the link); `GuardianOnboardingServiceTests` | added |
| Tenant from the security context, never trusted from the client | `EndpointSecurityAuditTests.aCollegeAdminNamingAnotherCollegesIdIsNeverServedOnAnyEndpointThatTakesATenantId` sweeps every GET endpoint that takes a `tenantId`; per-service `crossTenant…Denied` tests (about 25 services); `AiToolAuthorizationTests`, `AiPolicySearchTests` | added |
| Every row carries a tenant | `SchemaInvariantTests.everyTableIsTenantOwnedOrBelongsToATenantOwnedParent` (every table has `tenant_id` or a foreign-key path to one, plus a named platform allow-list) and `…everyTenantIdThatExistsIsNotNull…`; `MigrationIntegrationTests` | added |
| Role is not permission: user + role + permission + tenant + resource + ownership + state | `AuthorizationServiceTests`, per-service denial tests, `GuardianDelegationAuthorizationTests` | covered |
| Course vs section; class vs section | `CourseSectionTests`, `CourseTests`, `MigrationIntegrationTests` unique constraints | covered |
| Enrollment is stateful: prerequisites, capacity, conflicts, credit limit, holds, waitlist, overrides | `EnrollmentTests`, `EnrollmentOverrideTests`, `CoursePrerequisiteTests` (self-loop and cycle rejected), `EnrollmentConcurrencyTests` (last-seat race, double enrol) | covered |
| Attendance percentage is derived, never stored | `SchemaInvariantTests.attendancePercentageIsDerivedNeverStored`; `AttendanceRecordTests` (percentage views) | added |
| Grades: published are authoritative, corrections keep history and audit | `GradeTests`, `AssessmentConcurrencyTests` (concurrent correction, publish racing correction), `MigrationIntegrationTests.gradesAndTranscriptsAreAppendOnlyByDesign` | covered |
| Guardian access explicit, scoped, revocable, audited | `GuardianDelegatedLookupTests`, `GuardianDelegationAuthorizationTests` (expired, revoked), **`GuardianGrantLifecycleTests`** (grant and revoke each audited with the actor; revocation effective on the next request; a guardian cannot grant or revoke their own access) | added |
| AI has no unrestricted DB access; tools are application-owned and authorized | `AiToolAuthorizationTests`, `AiChatIntegrationTests` (cannot force SQL or switch tenant), **`AiExplainBoundaryTests`** (ArchUnit: no JPA/JDBC/datasource, no repository, no other module's infrastructure) | added |
| Authoritative write and outbox event commit atomically | **`OutboxAtomicityTests`** (the writer joins the caller's transaction; a rolled-back change leaves no event; a duplicate admission leaves no event), `OutboxPublisherTests` | added |
| Microservices by business ownership | Not applicable before Sprint 6; `ModularityTests` keeps the boundaries extraction depends on | covered |

## §5 Schema invariants

| Invariant | Evidence | Status |
|---|---|---|
| Enrollment unique per student and section | `MigrationIntegrationTests.criticalUniqueConstraintsExist`, `EnrollmentConcurrencyTests` | covered |
| Prerequisite graph rejects self-loops and cycles | `CoursePrerequisiteTests` | covered |
| Room capacity supports section capacity | `CourseSectionTests.roomCapacityInsufficientIsRejected` | covered |
| Binary media outside PostgreSQL | **`SchemaInvariantTests.binaryMediaStaysOutsidePostgres`** (only the three WebAuthn crypto columns; no large objects) | added |
| No card PAN/CVV stored | **`SchemaInvariantTests.paymentTablesNeverHoldCardNumbers`** | added |
| Optimistic locking on mutable aggregates | `MigrationIntegrationTests.optimisticLockingVersionColumnsArePresentOnMutableAggregates`, concurrency tests | covered |
| Official history preserved | `…gradesAndTranscriptsAreAppendOnlyByDesign`, versioned grades, append-only risk rows (`RiskConcurrencyTests`) | covered |

## §6 Authentication and authorization

| Requirement | Evidence | Status |
|---|---|---|
| Passkey validation (origin, RP ID, challenge, revoked credential) | `PasskeyCeremonyVerifierTests`, `PasskeyAuthenticationServiceTests`, `PasskeyFullFlowTests` | covered |
| Argon2id, breached-password policy, rate limits | `Argon2PasswordServiceTests`, `PasswordPolicy` tests, `AuthRateLimiterTests`, `PasswordResetCodeEndpointTests` | covered |
| Refresh token only as a hash, rotated every use, reuse detected | `SessionServiceTests`; **`SessionCookieAuditTests`** (rotation over HTTP, replay ends the login, raw token not in the database) | added |
| Refresh token only in a Secure, HttpOnly, SameSite cookie, never in a body | **`SessionCookieAuditTests.theRefreshTokenIsOnlyEverASecureHttpOnlyStrictCookieScopedToAuth`** | added |
| Step-up for privileged operations | `RoleAssignmentServiceTests`, `UserAccessServiceTests` (stale step-up), `PasskeyAuthenticationServiceTests` | covered |
| A revoked login or suspended account stops working at once | **Defect found and fixed**: access tokens stayed valid for up to 15 minutes after logout, suspension or refresh-token reuse, over HTTP and STOMP. Tokens are now bound to their login and re-checked on every request. **`AccessTokenRevocationTests`** (10) | fixed |
| Every endpoint refuses anonymous callers, except the named public ones | **`EndpointSecurityAuditTests.everyNonPublicEndpointRefusesAnAnonymousCaller`** (reads Spring's own handler mapping, so new controllers are covered automatically) | added |
| Privilege escalation, IDOR, tenant escape | per-service cross-tenant and wrong-role tests; the endpoint sweep above | covered |

## §7–§10 Realtime, events, Redis, AI

| Requirement | Evidence | Status |
|---|---|---|
| STOMP: connection authenticated, subscription authorized per destination, delivery only to the authorized | `AttendanceStompAuthorizationTests` (no token, bad token, wrong section, other tenant, student on the instructor board, delivery) | covered |
| STOMP: revoked session blocked | `AccessTokenRevocationTests` (same check `StompAuthorizationChannelInterceptor` uses via `TokenAuthenticationService`) | fixed |
| Consumers idempotent; retry; DLQ | `NotificationEventProcessorTests` (redelivery, 8 parallel deliveries, rollback of the event id), `NotificationErrorHandlerTests` | covered |
| A final grade completes the enrollment: one final-grade component per section (even created at once), the event keyed by the enrollment, idempotent on the event id, tenant checked, a correction changes the final status, a dropped student cannot be graded but a completed one can, the copy in an extracted service answers like Academic, a failing grade earns no credit and no prerequisite | **`FinalGradeComponentTests`**, **`FinalGradeEventProcessorTests`**, **`FinalGradeFlowTests`** (grade to transcript, end to end), `FinalGradePrerequisiteTests`, `FinalGradeTests`, `RecordDirectoryTests`, `AcademicReadModelParityTests.aCompletedEnrollmentIsGradableButNotActiveInTheCopyAsInAcademic`, the listener, error-handler and wiring tests | added |
| Risk recalculates on a grade event: idempotent on the event id, tenant checked against the enrollment, no actor, one recalculation per student at a time, refusals leave nothing behind, retry/DLQ policy that cannot be switched off by another consumer | **`RiskEventProcessorTests`**, **`RiskEventListenerTests`** (headers, version refusal, one flow from grade to alert), **`RiskErrorHandlerTests`**, **`RiskConsumerWiringTests`** | added |
| Redis never the source of truth | **`RedisOutageTests`** (sign-in and AI limiters fail open); `AuthRateLimiterTests.redisKeysNeverContainTheRawEmail` | added |
| AI: tool authorization, prompt injection, schema validation, tenant-safe retrieval, source attribution | `AiToolAuthorizationTests`, `AiChatIntegrationTests.ignoresAnInstructionEmbeddedInsideARetrievedPolicyDocument`, `AiPolicySearchTests`, `AiChatSourcesTests` | covered |
| RAG: tenant and document-ACL checked **before** retrieval, not after | **`PolicySearchRetrievalTests.theAccessCheckHappensBeforeRankingSoRestrictedPassagesCannotCrowdOutAllowedOnes`** (six better-matching restricted passages cannot displace the one allowed passage; an after-the-fact filter would return nothing), `…aTenantNeverSeesAnotherTenantsPassagesEvenWhenTheyAreIdentical` | added |
| RAG: embedder failure never blocks search or creation | `PolicySearchEmbedderDownTests` (full-text fallback, vector added on a later pass) | added |

## §13 Concurrency gate

Last-seat race, duplicate QR scan, duplicate payment event and concurrent grade update all have named tests:
`EnrollmentConcurrencyTests`, `AttendanceConcurrencyTests`, `FinanceConcurrencyTests`, `AssessmentConcurrencyTests`; plus learning,
career and risk concurrency tests.

## Other defects found by the audit

- `GET /enrollment/faculty-assignments` answered **500** when neither `sectionId` nor `facultyId` was given. It now answers 400.
  (Found by the tenant-escape sweep.)
- `docs/openapi-contract-catalog.md` described `POST /finance/payments/webhook` as a provider callback verified by a provider
  signature. The code is an authenticated stand-in (`POST /finance/payments/{paymentId}/events`, administrator only, idempotent on the
  provider event id); no provider signature scheme exists. The catalog now says so.

## Defects found by the Sprint 6C.2 failure/security/performance pass

Each is fixed and has a test that fails without the fix.

- **The gateway exposed `/actuator/**` unauthenticated** and proxied it onward. `ActuatorGuardFilter` now answers it itself;
  `ActuatorGuardFilterTests`. The services deny `/actuator/**` except health, info and a token-guarded Prometheus scrape
  (`ManagementEndpointsTests`).
- **No upper bound on page size.** `PageSizeLimitFilter` clamps `size` (`PageSizeLimitFilterTests`).
- **Entry-point defaults were silently shadowed** by `application.yml`, so every service would have started on port 8080 and named itself
  `setu-backend` in metrics and traces. `EntryPointDefaultsTests`, `EntryPointDefaultsPrecedenceTests`.
- **Connection-pool deadlock** under a burst (nested `REQUIRES_NEW` needs two connections per request). Sizing rule enforced by
  `ConnectionPoolSizingTests`.
- **Silent timeouts** on the model call and on the embedder; both now bounded and reported as unavailable, not as an empty answer
  (`AiChatSecondaryGeminiTests`, `SpringAiTextEmbedderTests`).
- **A log file nobody configured.** The 6C.1 logging configuration included Spring Boot's `base.xml`, which always attaches a rolling `spring.log`
  in the system temp directory; its clean-up job timing out failed application contexts at random on a slow disk. Console and OpenTelemetry
  only now, a file is opt-in (`file-logs` profile); `LoggingSystemTests`. Found by the full regression after the grade-event consumer was added.
- **Role versus assignment is inconsistent between services.** Learning and Attendance admit a faculty member by section assignment alone,
  while Assessment also requires the permission. This is a design property, not a leak (assignment is still required everywhere), so it is
  recorded, not changed here. See open item 9.

## Defects found by running the services as containers

The first time the extracted services ran as separate processes. Nine defects, none visible to a test that runs in one process; the full list, with
the fix and the check for each, is in [contracts/containers.md](contracts/containers.md). The ones that bear on the non-negotiables: no extracted
service could start (a Modulith endpoint needing a bean every entry point excludes), and no WebSocket of any service could connect (the remote security
chain rejected the handshake the monolith's chain opens). Both are now caught by tests that run in one process (`EntryPointDefaultsTests`,
`ExtractedServiceWiringSupport`), and by `infra/scripts/smoke-flow.js` and `smoke-websocket.js` against the running stack.

## Defect found while moving Record behind a port

- **A student's own transcript and degree progress failed with 403 once a course was completed.** The record read each enrollment's section
  through the checked section read, which lets a student see a section only while ENROLLED in it; a guardian holding a delegated grant has no role
  in the college that the checked course read demands. Nothing in the application completes an enrollment (open item below), so no test or demo ever
  reached it. The record now reads Academic through `RecordDirectory`, which carries no authorization of its own; `assertRead` is the single gate.
  `RecordDirectoryTests.aStudentCanStillReadTheirOwnTranscriptOnceACourseIsCompleted`. **The guardian path has no test yet.**

## Open items (no automated test, or a known gap)

1. ~~Event envelope is narrower than canonical spec §8.~~ **Closed in 6B.1**: events carry the envelope headers and schema/event
   versions; `EventEnvelopeTests` and `EventConsumerContractTests` pin them (`docs/contracts/kafka-contracts.md`).
2. **Client-supplied `tenantId` in request bodies** (for example `POST /career/jobs`) is authorized, and per-service cross-tenant tests
   cover many of them, but the generic sweep covers GET endpoints only; write endpoints still rely on those per-service tests.
3. **Access-token liveness costs two indexed queries per request** and fails closed on a database error. Acceptable at this scale; a
   short per-instance cache or the Redis denylist the spec mentions would be the optimisation, not a correctness change.
4. **Accessibility** (spec §13): fixed and checked by hand and with the browser's own tooling earlier, but there is no automated
   accessibility test in the frontend (no test runner is set up there).
5. ~~Performance: no N+1 detection test.~~ **Closed in 6C.2** for the paths measured: `QueryCountTests` (same statement count for 3 and
   20 rows), `DemoTargetsPerformanceTests` (spec §14 targets, tag `performance`), `ConnectionPoolSizingTests`. Still open: no load test
   over a real network and no Kafka-lag measurement (no consumer can run on this machine); see `docs/contracts/performance.md`.
6. ~~Contracts: event schemas have no compatibility test.~~ **Closed in 6B.1** (see 1). OpenAPI generation is tested (`OpenApiTests`).
7. **WebSocket fan-out and live QR through the dev bridge** cannot be exercised on the Windows development machine (known JDK defect);
   they are covered by `AttendanceStompAuthorizationTests`, which runs the real STOMP stack in-process.
8. **The broker-backed Kafka consumer test** (`NotificationKafkaConsumerTests`) skips itself where the JDK cannot open an NIO
   selector, which includes this machine; the retry and dead-letter policy is covered without a broker by `NotificationErrorHandlerTests`.
9. **Assigned-faculty access is not tied to the faculty role in Learning and Attendance** (Assessment requires the permission as well). A
   faculty assignment that outlives a role change would keep access to that section's learning and attendance data. Decide whether the role is
   required everywhere before relying on it; no test asserts either way yet.
10. **The gateway cannot proxy WebSocket upgrades** (it answers 501); STOMP clients connect to the Notification/Attendance service directly.
11. **Distributed state is verified in-process only.** Cross-service behavior (grant confirmation, remote AI facts, circuit breakers) is
    covered with blocking-socket stubs and MockMvc (`ExtractedServiceWiringSupport`, `GuardedCall` tests), not across real processes.
