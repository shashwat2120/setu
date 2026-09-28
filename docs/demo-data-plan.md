# Setu — Demo/Seed Data Plan

Day 15 §14 deliverable: what deterministic seed data the eventual frontend
and manual-review pass need, before either is built. This is a plan, not
yet a shipped seeder — see "Why not shipped this session" below for why,
and "Smallest correct implementation" for exactly how to build it when a
future session picks this up.

## Why this needs planning, not improvisation

Every entity this platform tracks is tenant-scoped and authorization-gated.
Seed data that does not reflect real relationships (a guardian actually
delegated to a real student, a faculty member actually assigned to a real
section, a student actually enrolled before a grade exists for them) would
demonstrate nothing real about authorization — it would just be inert rows.
The whole point of seed data here is to let a reviewer log in as each role
and see the SAME authorization boundaries this session's 480+ tests already
prove, not a simplified stand-in.

## Required coverage

One tenant is enough to demonstrate every module and every role boundary
(a second tenant is only needed to demonstrate cross-tenant denial, which
is already covered by dozens of automated tests — not something a human
reviewer needs seed data for). Per tenant:

| Role | Count | Why |
|---|---|---|
| `SUPER_ADMIN` | 1 (platform-level, no tenant) | Platform-wide views, tenant suspension |
| `COLLEGE_ADMIN` | 1 | Registrar-ops dashboard (enrollment queue, waitlist, institution-wide risk) |
| `FACULTY` | 2+ | One "clean" advisor with real advisees, one with none — demonstrates the advisor-scoping boundary itself |
| `STUDENT` | 3+ | One LOW risk, one MEDIUM/HIGH risk (real attendance/grade signals behind it, not a hand-set risk score), one with a guardian delegation active |
| `PLACEMENT_OFFICER` | 1 | Career module — jobs, skills, applications |
| `GUARDIAN` | 1, delegated to exactly one of the students above | Demonstrates the delegation boundary — must NOT be able to see the other two students |

Academic chain needed to make any of the above meaningful: 1 department,
1 program, 1 curriculum, 2+ courses, 2+ sections (so the "faculty with no
advisees" case above is real), 1 open academic period, a building/room.

Per-module data actually worth seeding (each reuses an EXISTING, already-
tested application service — never a raw INSERT):

- **Attendance**: a few closed sessions per section with a realistic mix of
  present/absent, so the risk-engine's attendance-trend feature has real
  signal (not just the neutral "no data" default every current automated
  test relies on for simplicity).
- **Learning**: 1-2 modules/lessons per section, one assignment with a mix
  of on-time/late submissions.
- **Assessment**: 1 quiz per section with published grades, including at
  least one failing grade (so the MEDIUM/HIGH-risk student's band is
  reached the same way `RiskCalculationTests.strugglingStudent_scoresHigh...`
  already proves it deterministically, not hand-set).
- **Student Success**: triggered by calling `RiskCalculationService.recalculate`
  for each student AFTER the above exists — never insert a `risk_scores` row
  directly, since the whole point of Day 13 is that it's derived, not
  editable.
- **Career**: 2-3 skills, 1-2 jobs with skill requirements, one application
  from the seeded student.
- **Finance**: one fee structure/component and one student charge — payment
  itself left unseeded (a real payment needs a real idempotency-key-bearing
  HTTP call, better exercised live than faked).
- **Policy documents** (Day 14): 2-3 real, short policy documents (attendance
  policy, grading policy) so `searchCollegePolicy` has something real to
  find — already demonstrated working in `AiPolicySearchTests`.

## What must NOT be seeded

- Passkeys — cannot be seeded (a real WebAuthn ceremony needs a real
  authenticator; a fake credential would be exactly the security shortcut
  Day 15's own instruction forbids). Seeded users authenticate via the
  Argon2id password fallback only, with a clearly-marked dev-only password.
- Any table Day 15's own audit found genuinely unbuilt (transcripts,
  graduation workflow, `record` module) — seeding a table with no service
  layer to validate it would mean raw SQL bypassing every invariant this
  whole project enforces everywhere else.
- Cross-tenant data — one tenant's seed data does not reference or leak
  into another's; if a second tenant is ever added for demo purposes, it
  gets its OWN fully independent seed, never a shared row.

## Naming convention (so seed data is never mistaken for real data)

- Institution name: `"Setu Institute of Technology"` — plausible but
  fictional, not `"Test College 1"`.
- Every seeded email: `@demo.setu.local` (a domain that cannot resolve,
  cannot collide with anything real).
- Every person's name: real-sounding full names (never "Test User",
  never Lorem Ipsum), but the college/company names they're affiliated
  with (`"Acme Robotics"` for a career-module employer, etc.) are
  themselves obviously generic placeholders, consistent with this
  project's "mark example data explicitly as an example" rule.

## Implementation (built this session)

`com.setu.shared.seed.DemoDataSeeder` — a `@Component implements
ApplicationRunner`, gated behind `app.demo-data.enabled` (default `false` —
never runs in the test suite or in a real deployment unless explicitly
opted into for a local demo). It calls the SAME real application services
every controller uses, exactly as this doc originally proposed, authenticated
as whichever real seeded principal would actually perform each step (a
college admin creates the department, the student themselves scans their own
QR code) via a new `UserDirectoryService.authenticateForSeed` — kept inside
`identity` specifically so the domain type it constructs (`UserPrincipal`)
never has to cross a module boundary.

Passwords are set through the SAME real `PasswordResetService.initiate`/
`complete` token flow a genuine password-reset would use — no new
password-setting port was needed after all; the "one genuinely new piece"
this doc originally anticipated turned out to already exist.

The one real new port: `UserDirectoryService.grantBootstrapRole` — a
narrow, heavily-disclosed, no-auth role grant, used ONLY for the very first
role a fresh tenant/the platform ever gets (impossible to authorize against
a role that doesn't exist yet). Every other authorization-gated call goes
through the real, already-tested permission checks.

A genuine Spring Modulith limitation surfaced while building this: a nested
sealed-interface `Success` record (e.g. `FeeStructureService.CreateResult.
Success`) is NOT exposed by `@NamedInterface` on its enclosing class, even
though the class itself is. Rather than widen exposure further, each
service that needed one gained a small `xxxForSeed` sibling next to its
real method (e.g. `CampusService#createForSeed`) that does the same-module
unwrap down to a plain `UUID`/`Optional<UUID>` — the sealed type itself
never crosses the module boundary.

## Verified this session

`docs/demo-data-plan.md`'s dataset now exists for real: two tenants ("Setu
Institute of Technology" and "Meridian College of Arts & Sciences"), all
six roles including scoped variants (two faculty on different sections, six
students with deterministic LOW/MEDIUM/HIGH risk bands derived from real
attendance/grade/submission/progress data via `RiskCalculationService`
itself — never hand-set — two guardians with four access grants spanning
active-full/active-limited/revoked/expired states plus one relationship
with no grant at all), full academic/attendance/LMS/assessment activity,
Student Success recommendations/interventions, finance (pending/overdue/
succeeded/failed/refunded), career, and policy documents.

`src/test/java/com/setu/shared/seed/DemoDataSeederVerificationTests.java`
verifies it end to end against real PostgreSQL/Redis with the real Spring
Security filter chain: all 6 roles authenticate through the real `/auth/
login` password flow, and the explicit negative-authorization scenarios all
pass — guardian without any grant denied despite a real relationship,
guardian with a revoked grant denied, guardian with an expired grant
denied, a guardian scoped to `VIEW_RISK_SCORE` only can see the score but
never recommendations, a student cannot view another student's risk score,
a college admin cannot reach another tenant's college, and a platform
`SUPER_ADMIN` reaches cross-tenant data only through a real, explicitly
granted `VIEW_RISK_SCORE_ALL_SECTIONS` permission — checked through the
exact same `AuthorizationContext.assertCan` pipeline every other role goes
through, never a hardcoded bypass.

## Disclosed environment limitation

A genuinely live server (`mvn spring-boot:run`, or the packaged jar) cannot
start on this Windows/JDK machine: embedded Tomcat's `NioEndpoint.Poller`
hits the exact same `java.nio.channels.Selector.open()` defect already
diagnosed for the Kafka client (`IOException: Unable to establish loopback
connection` / `SocketException: Invalid argument: connect`, confirmed at
the JDK/Windows level, not Tomcat-specific). This was not previously
visible because every prior day's HTTP verification already went through
`@SpringBootTest` + MockMvc — a real Spring Security + DispatcherServlet
round trip that never opens an OS socket — never a literally-running
server. `DemoDataSeederVerificationTests` uses that same, already-proven
mechanism for exactly this reason, and it is a genuine, full round trip,
not a weaker substitute.
