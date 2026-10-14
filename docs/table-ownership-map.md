# Setu — Table Ownership Map

Every table, its Sprint 5 owning module (strict — no cross-module direct table
access, TRD §2), and its Sprint 6 candidate service, per
[FINAL_CANONICAL_PROJECT_SPEC.md](FINAL_CANONICAL_PROJECT_SPEC.md) §12 plus
the approved additions in [sprint6-candidate-services.md](sprint6-candidate-services.md).
Decisions E, H1, H2, H3 are **approved and applied** below — no more open
questions from that round. `api_clients` is removed entirely (decision E).

| Table | Sprint 5 module | Sprint 6 candidate service |
|---|---|---|
| `users` | `identity` | Identity |
| `roles` | `identity` | Identity |
| `permissions` | `identity` | Identity |
| `role_permissions` | `identity` **(added Sprint 5B Day 4 — V53)** | Identity |
| `user_roles` | `identity` | Identity |
| `sessions` | `identity` | Identity |
| `passkeys` | `identity` | Identity |
| `password_reset_tokens` | `identity` **(added Sprint 5B Day 3 — V51)** | Identity |
| `password_reset_codes` | `identity` **(added with the emailed-code reset — V77)** | Identity |
| `webauthn_challenges` | `identity` **(added Sprint 5B Day 3 — V52)** | Identity |
| `persons` | `identity` **(moved from `directory` — H1)** | Identity |
| `student_profiles` | `directory` | Institution/Academic |
| `faculty_profiles` | `directory` | Institution/Academic |
| `guardians` | `directory` | Institution/Academic |
| `student_guardians` | `directory` | Institution/Academic |
| `guardian_access_grants` | `guardian-access` | Institution/Academic **(confirmed — H2, no separate Guardian service)** |
| `guardian_access_permissions` | `guardian-access` | Institution/Academic (confirmed — H2) |
| `colleges` | `institution` | Institution/Academic |
| `campuses` | `institution` | Institution/Academic |
| `academic_organizations` | `institution` | Institution/Academic |
| `departments` | `institution` | Institution/Academic |
| `policy_documents` | `institution` **(added Day 14 — V70; read by `ai-explain`'s `searchCollegePolicy` tool through a narrow application-service port, never a direct table reference)** | Institution/Academic |
| `academic_years` | `catalog` | Institution/Academic |
| `academic_periods` | `catalog` | Institution/Academic |
| `programs` | `catalog` | Institution/Academic |
| `curricula` | `catalog` | Institution/Academic |
| `curriculum_requirements` | `catalog` | Institution/Academic |
| `curriculum_requirement_courses` | `catalog` | Institution/Academic |
| `courses` | `catalog` | Institution/Academic |
| `course_prerequisites` | `catalog` | Institution/Academic |
| `buildings` | `catalog` | Institution/Academic |
| `rooms` | `catalog` | Institution/Academic |
| `meeting_patterns` | `catalog` | Institution/Academic |
| `degree_progress` | `catalog` **(moved from `record` — H3)** | Institution/Academic (confirmed — H3) |
| `classes` | `enrollment` | Institution/Academic |
| `class_students` | `enrollment` | Institution/Academic |
| `class_faculty` | `enrollment` | Institution/Academic |
| `course_sections` | `enrollment` | Institution/Academic |
| `faculty_assignments` | `enrollment` | Institution/Academic |
| `student_programs` | `enrollment` | Institution/Academic |
| `student_academic_periods` | `record` **(confirmed Record-owned — represents the student's official term snapshot)** | Academic Record *(Sprint 6 ownership remains a Day-16 validation question — see below)* |
| `enrollments` | `enrollment` | Institution/Academic |
| `enrollment_actions` | `enrollment` | Institution/Academic |
| `enrollment_overrides` | `enrollment` | Institution/Academic |
| `waitlist_entries` | `enrollment` | Institution/Academic |
| `attendance_sessions` | `attendance` | Attendance |
| `attendance_qr_sessions` | `attendance` | Attendance |
| `attendance_records` | `attendance` | Attendance |
| `faculty_attendance` | `attendance` | Attendance |
| `faculty_attendance_regularisations` | `attendance` | Attendance |
| `staff_attendance_policies` | `attendance` | Attendance |
| `course_modules` | `learning` | Learning |
| `lessons` | `learning` | Learning |
| `lesson_resources` | `learning` | Learning |
| `learning_progress` | `learning` | Learning |
| `assignments` | `learning` | Learning |
| `assignment_submissions` | `learning` | Learning |
| `assessments` | `assessment` | Assessment |
| `assessment_components` | `assessment` | Assessment |
| `questions` | `assessment` | Assessment |
| `assessment_attempts` | `assessment` | Assessment |
| `assessment_responses` | `assessment` | Assessment |
| `grades` | `assessment` | Assessment |
| `student_academic_records` | `record` | **Academic Record** (new candidate — H3) |
| `transcripts` | `record` | Academic Record |
| `graduation_applications` | `record` | Academic Record **(confirmed — Record consumes the eligibility result from Catalog/Academic's `degree_progress`, does not own curriculum rules)** |
| `fee_structures` | `finance` | Finance |
| `fee_components` | `finance` | Finance |
| `student_charges` | `finance` | Finance |
| `student_accounts` | `finance` | Finance |
| `payments` | `finance` | Finance |
| `payment_events` | `finance` | Finance |
| `receipts` | `finance` | Finance |
| `skills` | `career` | Career |
| `skill_categories` | `career` | Career |
| `student_skills` | `career` | Career |
| `jobs` | `career` | Career |
| `job_skills` | `career` | Career |
| `applications` | `career` | Career |
| `placement_records` | `career` | Career |
| `risk_scores` | `risk` | Student Success |
| `risk_factors` | `risk` | Student Success |
| `recommendations` | `risk` | Student Success |
| `interventions` | `risk` | Student Success |
| `intervention_actions` | `risk` | Student Success |
| `notifications` | `notification` | Notification/Realtime |
| `notification_preferences` | `notification` | Notification/Realtime |
| `delivery_attempts` | `notification` | Notification/Realtime |
| `processed_events` | `notification` | Notification/Realtime (the consumer's own idempotency table, V78) |
| `audit_logs` | *cross-cutting* | replicated per-service at extraction |
| `outbox_events` | *cross-cutting* | replicated per-service (service-owned data, canonical spec §2) |
| `idempotency_keys` | *cross-cutting* | replicated per-service as needed |
| ~~`api_clients`~~ | **removed — decision E** | — |
| *(`ai-explain` — no tables; reads `risk_scores`/`risk_factors` via authorized application call)* | `ai-explain` | AI |
| *(`analytics` — read-model projections, consumes events only)* | `analytics` | Analytics |

**Both placements below the table are now confirmed, per your documentation
clarification**: `student_academic_periods` is Record-owned in Sprint 5
(official term snapshot); Sprint 6 *service* ownership is still an open
Day-16 validation question, since it's more technically coupled to
`academic_periods` (Institution/Academic) than the rest of Record's tables
are. `graduation_applications` stays under Academic Record, consuming — not
owning — Catalog/Academic's eligibility determination.

## Column-level schema deviations (not new tables — noted here since they don't fit the row-per-table format above)

- **`institution.academic_organizations.parent_organization_id`** (added
  Sprint 5C Day 5 — V56): a nullable self-referencing FK, absent from both
  the original migration (V7) and the ERD
  ([erd/02-academic.md](erd/02-academic.md)), which modeled academic
  organizations as flat. The Day 5 instruction explicitly required a
  hierarchical structure (Faculty → Department-level sub-units) with cycle
  prevention, which a flat table cannot represent — see V56's own header
  comment. Cycle/tenant validation logic lives in
  `AcademicOrganizationService`, documented in its own class javadoc.
