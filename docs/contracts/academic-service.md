# Academic service contract (Sprint 6A.3)

What the Academic service is, what it needs from Identity, and how it gets it without a runtime dependency. It is the hub the other services
depend on (nine of them), so the rules here are the ones to get right first. Like Identity, it can already start on its own
(`AcademicServiceApplication`); everything in section 6 is enforced by a test that runs today.

## 1. What Academic owns

Five modules and their schemas: `institution` (colleges, campuses, organizations, departments, policy documents), `catalog` (programs,
curricula, courses, prerequisites, periods, rooms, meeting patterns), `enrollment` (sections, enrollments, waitlists, overrides, faculty
assignments, student programs), `directory` (student, faculty and guardian profiles) and `guardian_access` (guardian grants). Nothing
else reads or writes those tables. The service also owns the four read-model tables of section 3, in the `institution` schema.

Its public contracts for other services are the existing REST controllers and the events it publishes (enrollment, section and
attendance-session events: [kafka-event-catalog.md](../kafka-event-catalog.md)). It calls no other service.

## 2. What it no longer takes from Identity in-process

Before this pass the Academic modules reached Identity three ways: through shared interfaces Identity implemented (`AuthorizationContext`,
`TenantContext`, `TenantMembership`, `PersonDirectory`), through two direct calls to `UserDirectoryService` (`DepartmentService`,
`CourseService`), and through the authentication filter. Now:

| Needed | Before | Now |
|---|---|---|
| Who is calling | Identity's JWT filter, HS256 secret | `RemoteTokenFilter`: ES256 against Identity's published keys, then the revocation list ([identity-service.md](identity-service.md) sections 2 to 4) |
| `can(permission, resource)` | `AuthorizationService` reading Identity's tables | the same `AuthorizationEngine`, reading `ReadModelRoleGrantSource` |
| Tenant of the caller; "holds any role in tenant" | Identity's `TenantContextImpl`, `UserDirectoryService` | `RoleBasedTenantContext`, `RoleBasedTenantMembership` over the same read model |
| A person's name and contact | `identity.persons` | `ReadModelPersonDirectory` over `institution.rm_persons` (read-only) |
| `hasActiveRoleInTenant` in `DepartmentService`, `CourseService` | `UserDirectoryService` (a direct module dependency) | the `TenantMembership` interface; the module edges `institution -> identity` and `catalog -> identity` are gone from `module-contracts.txt` |
| Guardian delegation | `GuardianDelegationAdapter` | unchanged and now in-process with the engine: **a revoked grant takes effect on the next request**, with no cache |

The coupling report (`node backend/tools/coupling-report.js`) now shows no cycle between services and Academic with no outgoing
dependencies. The last open item of the audit's section 4 (the guardian-grant answer) is settled for Academic by the last row above;
the other services that authorize guardian access will need the grant data when they are extracted.

## 3. The read model

`institution.rm_role_grants`, `rm_role_permissions`, `rm_persons`, `rm_processed_events`. Written only by `IdentityEventProjector` (`shared.readmodel`, the same projector the services on top of Academic use for their own role copy, here with people enabled).

| Event (topic `setu.<aggregate>`) | Effect |
|---|---|
| `UserRoleAssigned` (`user_roles`) | insert the grant; never overwrites a grant a revocation already ended |
| `UserRoleRevoked` (`user_roles`) | upsert with `ends_at` = event time, so it works even if it arrives first |
| `PersonRegistered`, `PersonUpdated` (`persons`) | upsert name, preferred name, email, phone. Date of birth, gender and address never leave Identity |

- **Idempotent:** the event id goes into `rm_processed_events` in the same transaction as the change; a redelivery is a no-op.
- **Ordered per key:** the outbox publishes with the aggregate id as key, so events about one assignment or person arrive in order; the
  projector is also safe if they do not.
- **Consumer** (`shared.readmodel.kafka`, shared with the other extracted services): group `setu-academic`, offset `earliest`, so a new Academic service rebuilds its model by replaying the topics. Started in
  the background (`AcademicConsumerStarter`) and retried, so an unreachable broker never stops the service starting. Three deliveries with
  backoff, then `{topic}.DLQ`; malformed events skip the retries. Metrics `setu.academic.identity_events{event_type,outcome}`.
- **Staleness bound:** a role change reaches Academic when the outbox publisher delivers it and the consumer applies it (seconds), not
  when a token expires. Until then Academic decides from the previous state. A suspension or logout does not wait for this at all: those
  reach Academic through the revocation list.
- **Permission matrix:** reference data, copied once by migration `V80` and changed only by migration. A later migration that changes
  `identity.role_permissions` must change `institution.rm_role_permissions` in the same file; `ReadModelParityTests` fails if they drift.
- **Initial content:** `V80` copies the current rows from the identity schema. That works only while there is one database (6B.2 splits
  them); afterwards it becomes a snapshot export plus the replay.
- **Personal data:** `PersonRegistered`/`PersonUpdated` carry the legal name, email and phone on the `setu.persons` topic. That is the
  minimum Academic shows; restrict that topic's access like any other carrying personal data.

## 4. Running it alone

`AcademicServiceApplication` (`com.setu.academic`) scans `academic`, `institution`, `catalog`, `enrollment`, `directory`, `guardianaccess`
and `shared`, minus `shared.admin` and `shared.seed` (both span services). It sets `setu.service=academic`, `setu.auth.mode=remote`,
`app.academic.consumer.enabled=true`, port 8082, and does not run Flyway. `app.security.jwt.jwks-uri` (default
`http://localhost:8081/.well-known/jwks.json`) says where Identity's keys are. It is inert inside the monolith: every Academic-specific bean
is conditional on those properties, and the application class is a plain `@Configuration` so tests keep finding `BackendApplication`.

## 5. Not part of this service yet

- **Onboarding** (`shared.admin`: admit a student, onboard faculty or a guardian) creates an Identity account, a person and a profile in
  one transaction. Across services that is a saga (create the account, then the person, then the profile, compensate on failure). Until it
  is built, those endpoints exist only in the monolith.
- **Section cancellation does not drop enrollments** (audit section 9), and the demo seeder is still a monolith-only class.
- **Foreign keys to Identity** (`directory.*.person_id` to `identity.persons`, `guardian_access_permissions` to `identity.permissions`) and
  the one back (`identity.user_roles.tenant_id` to `institution.colleges`) stay until 6B.2 gives each service its own database; they are
  frozen in `cross-service-foreign-keys.txt`.
- **Routing** through the gateway is 6A.4. A real Tomcat on this development machine is blocked by the Windows NIO defect noted in
  `backend/README.md`, so the service has been exercised through MockMvc, not over a socket.

## 6. Tests that hold this contract

| Contract | Test |
|---|---|
| The decision rules, regardless of where roles come from | `AuthorizationEngineTests` |
| The read model gives Identity's grants, permissions, decisions and people, through the real services and outbox events; replay, duplicates, out-of-order revocation, poison events, no extra personal data | `ReadModelParityTests` |
| The service starts with only Academic's modules and the kernel; authorizes from the read model; refuses bad, foreign-key, unknown-key and revoked tokens; honours an ended role at once | `AcademicServiceWiringTests` |
| No module edge from institution/catalog to identity; four read-model tables owned by Academic | `ModuleContractFreezeTests`, `TableOwnershipTests` |
| Identity's own authorization is unchanged by the refactor into the shared engine | `AuthorizationServiceTests`, `GuardianDelegationAuthorizationTests` and the rest of the suite |
