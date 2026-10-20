# Event schemas

Generated from the event classes by `EventSchemaFreezeTests` (`-Dfreeze.update=true`); do not edit by hand. Every event has the canonical
envelope in its Kafka headers (see [contracts/kafka-contracts.md](contracts/kafka-contracts.md)); this lists what varies per event.
`Topic` is `setu.<aggregate>`, and the record key is the aggregate id, so one aggregate's events stay in order.

**50 events from 11 producers.**

## assessment

| Event | Version | Topic | Payload fields |
|---|---|---|---|
| `FinalGradePublished` | 1 | `setu.grades` | `gradeId` UUID, `enrollmentId` UUID, `componentId` UUID, `version` int, `supersedesId` UUID, `gradeCode` String, `gradePoint` BigDecimal, `publishedBy` UUID |
| `GradePublished` | 1 | `setu.grades` | `gradeId` UUID, `enrollmentId` UUID, `componentId` UUID, `version` int, `supersedesId` UUID |

## attendance

| Event | Version | Topic | Payload fields |
|---|---|---|---|
| `AttendanceRecorded` | 1 | `setu.attendance_records` | `attendanceRecordId` UUID, `sessionId` UUID, `enrollmentId` UUID, `status` String, `markedAt` Instant |
| `AttendanceSessionClosed` | 1 | `setu.attendance_sessions` | `sessionId` UUID, `sectionId` UUID, `closedAt` Instant, `absentRecordsGenerated` int |
| `AttendanceSessionOpened` | 1 | `setu.attendance_sessions` | `sessionId` UUID, `sectionId` UUID, `meetingId` UUID |
| `FacultyAttendanceCorrected` | 1 | `setu.faculty_attendance` | `eventType` String, `facultyAttendanceId` UUID, `facultyId` UUID, `attendanceDate` String, `status` String, `previousStatus` String, `checkInAt` Instant, `checkOutAt` Instant, `actorUserId` UUID, `reason` String, `lateMinutes` Integer, `workedMinutes` Integer, `policyVersion` Integer |
| `FacultyAttendanceMarked` | 1 | `setu.faculty_attendance` | `eventType` String, `facultyAttendanceId` UUID, `facultyId` UUID, `attendanceDate` String, `status` String, `previousStatus` String, `checkInAt` Instant, `checkOutAt` Instant, `actorUserId` UUID, `reason` String, `lateMinutes` Integer, `workedMinutes` Integer, `policyVersion` Integer |
| `FacultyAttendancePolicyChanged` | 1 | `setu.faculty_attendance_policy` | `policyVersion` int, `shiftStart` String, `shiftEnd` String, `graceMinutes` int, `fullDayMinutes` int, `halfDayMinutes` int, `actorUserId` UUID |
| `FacultyCheckedIn` | 1 | `setu.faculty_attendance` | `eventType` String, `facultyAttendanceId` UUID, `facultyId` UUID, `attendanceDate` String, `status` String, `previousStatus` String, `checkInAt` Instant, `checkOutAt` Instant, `actorUserId` UUID, `reason` String, `lateMinutes` Integer, `workedMinutes` Integer, `policyVersion` Integer |
| `FacultyCheckedOut` | 1 | `setu.faculty_attendance` | `eventType` String, `facultyAttendanceId` UUID, `facultyId` UUID, `attendanceDate` String, `status` String, `previousStatus` String, `checkInAt` Instant, `checkOutAt` Instant, `actorUserId` UUID, `reason` String, `lateMinutes` Integer, `workedMinutes` Integer, `policyVersion` Integer |
| `FacultyRegularisationApproved` | 1 | `setu.faculty_attendance` | `eventType` String, `regularisationId` UUID, `facultyId` UUID, `attendanceDate` String, `requestedStatus` String, `status` String, `actorUserId` UUID, `reasonOrNote` String |
| `FacultyRegularisationRejected` | 1 | `setu.faculty_attendance` | `eventType` String, `regularisationId` UUID, `facultyId` UUID, `attendanceDate` String, `requestedStatus` String, `status` String, `actorUserId` UUID, `reasonOrNote` String |
| `FacultyRegularisationRequested` | 1 | `setu.faculty_attendance` | `eventType` String, `regularisationId` UUID, `facultyId` UUID, `attendanceDate` String, `requestedStatus` String, `status` String, `actorUserId` UUID, `reasonOrNote` String |
| `FacultyRegularisationWithdrawn` | 1 | `setu.faculty_attendance` | `eventType` String, `regularisationId` UUID, `facultyId` UUID, `attendanceDate` String, `requestedStatus` String, `status` String, `actorUserId` UUID, `reasonOrNote` String |

## career

| Event | Version | Topic | Payload fields |
|---|---|---|---|
| `PlacementRecorded` | 1 | `setu.placement_records` | `placementId` UUID, `studentId` UUID, `employer` String |

## catalog

| Event | Version | Topic | Payload fields |
|---|---|---|---|
| `MeetingPatternRemoved` | 1 | `setu.meeting_patterns` | `meetingPatternId` UUID, `sectionId` UUID |
| `MeetingPatternScheduled` | 1 | `setu.meeting_patterns` | `meetingPatternId` UUID, `sectionId` UUID |

## directory

| Event | Version | Topic | Payload fields |
|---|---|---|---|
| `FacultyProfileCreated` | 1 | `setu.faculty_profiles` | `facultyId` UUID, `ownerUserId` UUID, `status` String |
| `FacultyStatusChanged` | 1 | `setu.faculty_profiles` | `facultyId` UUID, `status` String |
| `StudentAdmitted` | 1 | `setu.student_profiles` | `studentId` UUID, `admissionNo` String, `admissionDate` LocalDate, `ownerUserId` UUID |

## enrollment

| Event | Version | Topic | Payload fields |
|---|---|---|---|
| `CourseSectionCancelled` | 1 | `setu.course_sections` | `sectionId` UUID, `affectedEnrollmentIds` List<UUID> |
| `CourseSectionCreated` | 1 | `setu.course_sections` | `sectionId` UUID, `courseId` UUID, `periodId` UUID |
| `CourseSectionOpened` | 1 | `setu.course_sections` | `sectionId` UUID, `courseId` UUID, `periodId` UUID |
| `EnrollmentCompleted` | 1 | `setu.enrollments` | `enrollmentId` UUID, `sectionId` UUID, `studentId` UUID, `periodId` UUID, `finalGrade` String, `passed` boolean, `corrected` boolean |
| `EnrollmentDropped` | 1 | `setu.enrollments` | `enrollmentId` UUID, `sectionId` UUID, `studentId` UUID, `droppedAt` Instant, `wasEnrolled` boolean |
| `FacultyAssigned` | 1 | `setu.faculty_assignments` | `assignmentId` UUID, `sectionId` UUID, `facultyId` UUID, `endsAt` LocalDate |
| `FacultyAssignmentEnded` | 1 | `setu.faculty_assignments` | `assignmentId` UUID, `sectionId` UUID, `facultyId` UUID, `endsAt` LocalDate |
| `PeriodClosed` | 1 | `setu.academic_periods` | `periodId` UUID, `academicYearId` UUID, `periodCode` String |
| `StudentEnrolled` | 1 | `setu.enrollments` | `enrollmentId` UUID, `sectionId` UUID, `studentId` UUID, `viaWaitlist` boolean |
| `StudentWaitlisted` | 1 | `setu.enrollments` | `enrollmentId` UUID, `sectionId` UUID, `studentId` UUID, `position` int |
| `WaitlistConverted` | 1 | `setu.waitlist_entries` | `waitlistEntryId` UUID, `enrollmentId` UUID, `sectionId` UUID, `studentId` UUID |

## finance

| Event | Version | Topic | Payload fields |
|---|---|---|---|
| `PaymentFailed` | 1 | `setu.payments` | `paymentId` UUID, `studentId` UUID, `reason` String |
| `PaymentRefunded` | 1 | `setu.payments` | `paymentId` UUID, `studentId` UUID, `amount` BigDecimal, `refundedBy` UUID |
| `PaymentSuccessful` | 1 | `setu.payments` | `paymentId` UUID, `studentId` UUID, `amount` BigDecimal, `currency` String |

## guardianaccess

| Event | Version | Topic | Payload fields |
|---|---|---|---|
| `GuardianAccessGranted` | 1 | `setu.guardian_access_grants` | `grantId` UUID, `studentId` UUID, `guardianUserId` UUID, `expiresAt` Instant, `permissions` List<String> |
| `GuardianAccessRevoked` | 1 | `setu.guardian_access_grants` | `grantId` UUID, `studentId` UUID |

## identity

| Event | Version | Topic | Payload fields |
|---|---|---|---|
| `PasskeyRevoked` | 1 | `setu.passkeys` | `passkeyId` UUID, `userId` UUID, `revokedBy` UUID |
| `PersonRegistered` | 1 | `setu.persons` | `personId` UUID, `userId` UUID, `legalName` String, `preferredName` String, `email` String, `phone` String |
| `PersonUpdated` | 1 | `setu.persons` | `personId` UUID, `userId` UUID, `legalName` String, `preferredName` String, `email` String, `phone` String |
| `SessionRevoked` | 1 | `setu.sessions` | `sessionId` UUID, `userId` UUID, `reason` String |
| `UserDeactivated` | 1 | `setu.users` | `userId` UUID, `actorUserId` UUID, `selfInitiated` boolean |
| `UserReactivated` | 1 | `setu.users` | `userId` UUID, `reason` String, `actorUserId` UUID |
| `UserRoleAssigned` | 1 | `setu.user_roles` | `userRoleId` UUID, `userId` UUID, `roleCode` String, `actorUserId` UUID |
| `UserRoleRevoked` | 1 | `setu.user_roles` | `userRoleId` UUID, `userId` UUID, `roleCode` String, `actorUserId` UUID |
| `UserSuspended` | 1 | `setu.users` | `userId` UUID, `reason` String, `actorUserId` UUID |

## institution

| Event | Version | Topic | Payload fields |
|---|---|---|---|
| `CollegeCreated` | 1 | `setu.colleges` | `name` String, `timezone` String |
| `CollegeUpdated` | 1 | `setu.colleges` | `name` String, `timezone` String |

## risk

| Event | Version | Topic | Payload fields |
|---|---|---|---|
| `InterventionCompleted` | 1 | `setu.interventions` | `interventionId` UUID, `outcome` String |
| `InterventionCreated` | 1 | `setu.interventions` | `interventionId` UUID, `studentId` UUID, `ownerUserId` UUID |
| `RecommendationCreated` | 1 | `setu.recommendations` | `recommendationId` UUID, `studentId` UUID, `generatedBy` GeneratedBy |
| `RiskScoreChanged` | 1 | `setu.risk_scores` | `riskScoreId` UUID, `studentId` UUID, `oldBand` RiskBand, `newBand` RiskBand, `ruleVersion` String |
