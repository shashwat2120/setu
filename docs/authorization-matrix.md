# Setu — Authorization Matrix

Pre-coding design artifact. Every row is evaluated through the pipeline in
[FINAL_CANONICAL_PROJECT_SPEC.md](FINAL_CANONICAL_PROJECT_SPEC.md) §6: authenticate
→ resolve tenant → permission → resource ownership/assignment → workflow/state
→ explicit grant if applicable → allow/deny → audit. "Scope" below is the
ownership/assignment check specifically — role alone never decides access.

Legend: **Own** = the actor's own record; **Assigned** = faculty's own
sections/advisees; **Tenant** = anything in the actor's current tenant;
**Granted** = guardian, limited to what a `guardian_access_grant` allows;
**Platform** = cross-tenant, `SUPER_ADMIN` only.

## Institution, academic structure

| Resource | SUPER_ADMIN | COLLEGE_ADMIN | FACULTY | STUDENT | PLACEMENT_OFFICER | GUARDIAN |
|---|---|---|---|---|---|---|
| College (create/suspend/archive) | Platform, full | — | — | — | — | — |
| Campus/org/department | Platform | Tenant, full | Read (assigned dept) | — | — | — |
| Academic year/period | Platform | Tenant, full | Read | Read | Read | — |
| Program/curriculum | Platform | Tenant, full | Read | Read (own program) | — | — |
| Course (catalog) | Platform | Tenant, full | Read, propose (needs admin approval) | Read | — | — |
| Course section (schedule) | Platform | Tenant, full | Read/update own assigned sections (roster notes, not capacity/schedule) | Read | — | — |

## People, guardians

| Resource | SUPER_ADMIN | COLLEGE_ADMIN | FACULTY | STUDENT | PLACEMENT_OFFICER | GUARDIAN |
|---|---|---|---|---|---|---|
| Student profile | Platform | Tenant, full | Read (assigned sections only) | Own, read + limited self-update (contact info) | Read (career scope only — see career note below) | Read (Granted student only, per grant permissions) |
| Faculty profile | Platform | Tenant, full | Own, read + self-update | — | — | — |
| Guardian relationship (`student_guardians`) | Platform | Tenant, create/edit | — | Read own | — | Read own |
| Guardian access grant | Platform | Tenant, create/revoke | — | Create/revoke grants for their own guardians (self-service) | — | — (cannot self-grant; a guardian never controls their own access) |
| PII fields (dob, address, phone) | Platform | Tenant, per policy | Read only if operationally necessary for the assigned relationship (canonical spec §6: "no blanket parent details") | Own, full | Explicitly denied — career scope excludes PII | Only if the specific grant includes it |

## Enrollment, attendance

| Resource | SUPER_ADMIN | COLLEGE_ADMIN | FACULTY | STUDENT | PLACEMENT_OFFICER | GUARDIAN |
|---|---|---|---|---|---|---|
| Enrollment (create/drop) | Platform | Tenant, full incl. overrides | Read (assigned sections) | Own, create/drop within rules | — | Read own (if `VIEW_SCHEDULE` granted) |
| Enrollment override | Platform | Tenant, approve | Propose (needs admin approval) | Request | — | — |
| Waitlist | Platform | Tenant, full | Read (assigned sections) | Own | — | Read own (if granted) |
| Attendance session (open/close) | Platform | Tenant | Own assigned sections only | — | — | — |
| Attendance record (mark/correct) | Platform | Tenant, correct with audit | Own assigned sections, mark + correct with audit | Own, read only (scan is the "write," via the student-facing scan endpoint, not a direct record edit) | — | Read own (if `VIEW_ATTENDANCE` granted) |

## Learning, assessment, records

| Resource | SUPER_ADMIN | COLLEGE_ADMIN | FACULTY | STUDENT | PLACEMENT_OFFICER | GUARDIAN |
|---|---|---|---|---|---|---|
| LMS content (modules/lessons) | Platform | Tenant | Own assigned sections, full | Read (enrolled sections only) | — | — |
| Learning progress | Platform | Tenant, read | Own assigned sections, read | Own, read + write (self-paced progress) | — | — |
| Assignment/submission | Platform | Tenant, read | Own assigned sections, full (incl. grading) | Own, create/submit | — | — |
| Assessment (author/publish) | Platform | Tenant, approve | Own assigned sections, author + publish | Read (once published) | — | — |
| Assessment attempt | Platform | Tenant, read | Own assigned sections, read + evaluate | Own, create/submit | — | — |
| Grade (publish/correct) | Platform | Tenant, correct with audit | Own assigned sections, publish + correct with audit | Own, read only | — | Read own (if `VIEW_GRADES` granted) |
| Transcript/degree progress | Platform | Tenant, generate | Read (advisees) | Own, read + request generation | — | Read own (if `VIEW_ACADEMIC_STATUS` granted) |
| Graduation application | Platform | Tenant, review/approve | — | Own, submit | — | — |

## Finance, career, success

| Resource | SUPER_ADMIN | COLLEGE_ADMIN | FACULTY | STUDENT | PLACEMENT_OFFICER | GUARDIAN |
|---|---|---|---|---|---|---|
| Charges/payments/receipts | Platform | Tenant, full | — | Own, read + initiate payment | — | Read own (if `VIEW_FINANCE` granted — **note**: not in the default guardian ceiling per [contradiction audit #6](contradiction-audit.md#6-guardian-grant-policy-ceiling-was-undefined); a tenant must explicitly add it) |
| Skills/jobs/applications | Platform | Tenant, read | — | Own, read + apply | Tenant, full (career scope only — **no** grades/attendance/PII access, per canonical spec §6 "parent data not part of routine placement scope," which by extension bounds placement-officer scope to career data only) | — |
| Placement records | Platform | Tenant, read | — | Own, read | Tenant, full | — |
| Risk score / factors | Platform | Tenant, read | Own advisees, read | Own, read (with AI explanation) | — | Read own (if granted — narrow default) |
| Recommendation / intervention | Platform | Tenant, full | Own advisees, create/manage | Own, read | — | — |

## AI, RAG, platform

| Resource | SUPER_ADMIN | COLLEGE_ADMIN | FACULTY | STUDENT | PLACEMENT_OFFICER | GUARDIAN |
|---|---|---|---|---|---|---|
| AI explanation request | Platform | Tenant, on any student | On advisees, via `getStudentAttendance` etc. tool scope | Own, self only | — | Not exposed to guardians in v1 (AI explanations are a student/advisor feature; flagged as a scope decision, not a gap — extend later if needed) |
| RAG policy search | Platform | Tenant | Tenant (document ACL still applies per-document) | Tenant (document ACL still applies) | Tenant | — |
| Notifications | Platform | Tenant, send | Own assigned scope | Own, read | Own scope | Own, read (if granted) |
| Audit logs | Platform, full | Tenant, read | — | — | — | — |

## Explicit denials worth stating (not just omissions)

- **FACULTY never gets blanket guardian/parent data** — canonical spec §11
  ("no blanket parent details; only necessary authorized information") is
  enforced by *never* granting `FACULTY` a guardian-scope permission at all,
  not by relying on UI to hide a field the API would otherwise return.
- **GUARDIAN never grants their own access** — `guardian_access_grants` write
  permission belongs to `COLLEGE_ADMIN` and the *student* (self-service), never
  the guardian themself. This directly implements the canonical spec §15 ban
  on a guardian "master switch."
- **PLACEMENT_OFFICER is scoped to career data only** — no grades, no
  attendance, no PII beyond what's needed for a job application. This is
  narrower than a plain "role" check would produce if permissions were
  assigned carelessly; it's enforced by simply never issuing
  attendance/grade/PII permissions to that role, not by a runtime carve-out.
- **AI tool calls carry the *calling user's* authorization, not their own** —
  `ai-explain` never has standing permissions; every tool call re-runs the
  full pipeline as the user who asked the question (TRD §6).
