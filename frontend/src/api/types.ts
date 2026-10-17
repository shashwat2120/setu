export interface PageResponse<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface StudentResponse {
  id: string;
  tenantId: string;
  personId: string;
  userId: string | null;
  admissionNo: string;
  status: string;
  fullName: string | null;
  email: string | null;
}

/** identity.users.status — account-level login access, independent of the profile status above. See UserAccessService. */
export type AccountStatus = 'ACTIVE' | 'SUSPENDED' | 'DEACTIVATED';

export interface TranscriptGradeLine {
  componentId: string;
  rawMarks: number | null;
  gradeCode: string | null;
  gradePoint: number | null;
}

export interface TranscriptLine {
  enrollmentId: string;
  courseCode: string | null;
  courseTitle: string | null;
  credits: number | null;
  sectionCode: string | null;
  enrollmentStatus: string;
  grades: TranscriptGradeLine[];
  /** Weighted rollup of this course's published component grade points; null until something is published. */
  courseGradePoint: number | null;
  /** The grade the course ended with, once its final grade is published (which completes the enrollment); null until then. */
  finalGrade: string | null;
}

export interface TranscriptResponse {
  lines: TranscriptLine[];
  gpa: number | null;
}

export interface DegreeProgressResponse {
  creditsCompleted: number;
  creditsRequired: number;
  percentage: number;
  hasActiveProgram: boolean;
}

export interface FacultyResponse {
  id: string;
  tenantId: string;
  personId: string;
  userId: string | null;
  employeeNo: string;
  status: string;
  fullName: string | null;
  email: string | null;
}

export interface CourseResponse {
  id: string;
  tenantId: string;
  departmentId: string;
  code: string;
  title: string;
  credits: number;
  courseType: string;
  level: string | null;
  description: string | null;
  status: string;
}

export interface ProgramResponse {
  id: string;
  tenantId: string;
  departmentId: string;
  code: string;
  name: string;
  status: string;
}

export interface CurriculumResponse {
  id: string;
  tenantId: string;
  programId: string;
  name: string;
  versionLabel: string;
  effectiveFrom: string;
  effectiveTo: string | null;
}

export interface BuildingResponse {
  id: string;
  tenantId: string;
  code: string;
  name: string;
}

export interface PrerequisiteResponse {
  id: string;
  courseId: string;
  prerequisiteCourseId: string;
  ruleType: string;
  minimumGrade: string | null;
}

export interface RoomResponse {
  id: string;
  tenantId: string;
  buildingId: string;
  code: string;
  capacity: number;
  featuresJson: string | null;
}

export interface MeetingPatternResponse {
  id: string;
  tenantId: string;
  sectionId: string;
  dayOfWeek: number;
  startTime: string;
  endTime: string;
  roomId: string;
  effectiveFrom: string;
  effectiveTo: string | null;
}

export interface CourseSectionResponse {
  id: string;
  tenantId: string;
  courseId: string;
  periodId: string;
  classId: string | null;
  sectionCode: string;
  capacity: number;
  roomId: string | null;
  status: string;
  registrationOpenAt: string | null;
  registrationCloseAt: string | null;
}

export interface FacultyAssignmentResponse {
  id: string;
  tenantId: string;
  sectionId: string;
  facultyId: string;
  role: string;
  allocationPct: number;
  startsAt: string | null;
  endsAt: string | null;
  active: boolean;
}

export interface EnrollmentResponse {
  id: string;
  tenantId: string;
  sectionId: string;
  studentId: string;
  status: string;
  requestedAt: string | null;
  enrolledAt: string | null;
  droppedAt: string | null;
  finalStatus: string | null;
}

export interface AttendanceSessionResponse {
  id: string;
  tenantId: string;
  sectionId: string;
  meetingId: string;
  startedAt: string;
  endsAt: string;
  status: string;
}

export interface AttendanceOpenResponse {
  sessionId: string;
  qrToken: string;
  qrExpiresAt: string;
}

export interface AttendanceRecordResponse {
  id: string;
  attendanceSessionId: string;
  enrollmentId: string;
  status: string;
  markedAt: string;
  source: string;
  correctedBy: string | null;
}

export interface RiskScoreResponse {
  id: string;
  tenantId: string;
  studentId: string;
  score: number;
  band: string;
  ruleVersion: string;
  featureSnapshotHash: string;
  featureSnapshot: string;
  calculatedAt: string;
  factors: { id: string; factorCode: string; contribution: number; evidenceRefs: string }[];
}

// --- Institution ---

export interface CollegeResponse {
  id: string;
  code: string;
  name: string;
  timezone: string;
  locale: string;
  status: string;
}

export interface CampusResponse {
  id: string;
  tenantId: string;
  code: string;
  name: string;
}

export interface OrganizationResponse {
  id: string;
  tenantId: string;
  parentOrganizationId: string | null;
  name: string;
}

export interface DepartmentResponse {
  id: string;
  tenantId: string;
  organizationId: string;
  name: string;
  hodUserId: string | null;
}

export interface AcademicYearResponse {
  id: string;
  tenantId: string;
  code: string;
  startDate: string;
  endDate: string;
  status: string;
}

export interface AcademicPeriodResponse {
  id: string;
  tenantId: string;
  academicYearId: string;
  code: string;
  name: string;
  sequence: number;
  startDate: string;
  endDate: string;
  registrationOpenAt: string | null;
  registrationCloseAt: string | null;
  status: string;
}

// --- Enrollment (admin-facing extras) ---

export interface EnrollmentActionResponse {
  id: string;
  action: string;
  actorUserId: string;
  reason: string | null;
  createdAt: string;
}

export interface EnrollmentOverrideResponse {
  id: string;
  tenantId: string;
  studentId: string;
  sectionId: string;
  type: string;
  status: string;
  reason: string | null;
  approvedBy: string;
  expiresAt: string | null;
}

export interface WaitlistEntryResponse {
  id: string;
  tenantId: string;
  sectionId: string;
  studentId: string;
  position: number;
  status: string;
  createdAt: string;
}

export interface StudentProgramResponse {
  id: string;
  tenantId: string;
  studentId: string;
  programId: string;
  status: string;
  primary: boolean;
}

// --- Assessment ---

export interface AssessmentResponse {
  id: string;
  tenantId: string;
  sectionId: string;
  type: string;
  title: string;
  totalMarks: number;
  durationMinutes: number | null;
  startsAt: string;
  endsAt: string;
  status: string;
}

export interface AssessmentComponentResponse {
  id: string;
  assessmentId: string;
  code: string;
  maxMarks: number;
  weight: number;
  orderNo: number;
  /** This component's published grade is the course's final grade: publishing it completes the student's enrollment. */
  finalGrade: boolean;
}

export interface GradeResponse {
  id: string;
  tenantId: string;
  enrollmentId: string;
  componentId: string;
  rawMarks: number;
  gradeCode: string;
  gradePoint: number;
  status: string;
  version: number;
  supersedesId: string | null;
  isCorrection: boolean;
}

// --- Finance ---

export interface FeeStructureResponse {
  id: string;
  tenantId: string;
  programId: string;
  name: string;
  academicYearId: string;
  status: string;
}

export interface FeeComponentResponse {
  id: string;
  feeStructureId: string;
  code: string;
  amount: number;
  currency: string;
}

export interface StudentAccountResponse {
  id: string;
  tenantId: string;
  studentId: string;
  balance: number;
  currency: string;
}

export interface StudentChargeResponse {
  id: string;
  tenantId: string;
  studentId: string;
  feeComponentId: string;
  amount: number;
  currency: string;
  dueDate: string;
  status: string;
}

// --- Career ---

export interface SkillResponse {
  id: string;
  tenantId: string;
  code: string;
  name: string;
  categoryId: string | null;
  version: number;
}

export interface JobResponse {
  id: string;
  tenantId: string;
  postedBy: string;
  title: string;
  employer: string;
  description: string;
  status: string;
  version: number;
}

export interface ApplicationResponse {
  id: string;
  tenantId: string;
  jobId: string;
  studentId: string;
  status: string;
  appliedAt: string;
  notes: string | null;
}

export interface PlacementRecordResponse {
  id: string;
  tenantId: string;
  studentId: string;
  jobId: string;
  employer: string;
  role: string;
  ctc: number;
  placedAt: string;
}

// --- Risk / Student Success ---

export interface RecommendationResponse {
  id: string;
  riskScoreId: string;
  actionCode: string;
  generatedBy: string;
  status: string;
}

export interface InterventionActionResponse {
  id: string;
  action: string;
  actorUserId: string;
  notes: string | null;
  createdAt: string;
}

export interface InterventionResponse {
  id: string;
  tenantId: string;
  studentId: string;
  riskScoreId: string;
  recommendationId: string;
  ownerUserId: string;
  status: string;
  dueDate: string | null;
  actions: InterventionActionResponse[];
}

// --- Learning ---

export interface CourseModuleResponse {
  id: string;
  tenantId: string;
  sectionId: string;
  title: string;
  sequence: number;
  status: string;
}

export interface LessonResponse {
  id: string;
  moduleId: string;
  title: string;
  type: string | null;
  contentRef: string | null;
  sequence: number;
  durationMinutes: number | null;
}

export interface LearningAssignmentResponse {
  id: string;
  tenantId: string;
  sectionId: string;
  title: string;
  maxMarks: number;
  dueAt: string;
  latePolicy: string | null;
  status: string;
}

export interface AssignmentSubmissionResponse {
  id: string;
  assignmentId: string;
  studentId: string;
  attemptNo: number;
  submittedAt: string | null;
  status: string;
  contentRef: string | null;
  marks: number | null;
  feedback: string | null;
  gradedBy: string | null;
  gradedAt: string | null;
}

export interface LearningProgressResponse {
  id: string;
  lessonId: string;
  studentId: string;
  state: string | null;
  percent: number | null;
  startedAt: string | null;
  completedAt: string | null;
  lastPosition: string | null;
}

export interface LessonResourceResponse {
  id: string;
  lessonId: string;
  mediaRef: string;
  type: string;
  title: string | null;
}

export interface AssessmentAttemptResponse {
  id: string;
  assessmentId: string;
  studentId: string;
  attemptNo: number;
  startedAt: string;
  submittedAt: string | null;
  status: string;
  score: number | null;
}

export interface QuestionResponse {
  id: string;
  componentId: string;
  type: string;
  prompt: string;
  points: number;
  difficulty: string | null;
  skillId: string | null;
  metadataJson: string | null;
}

export interface AssessmentResponseRecord {
  id: string;
  attemptId: string;
  questionId: string;
  answerJson: string;
  isCorrect: boolean | null;
  pointsAwarded: number | null;
}

export interface PaymentInitiateResponse {
  paymentId: string;
  replayed: boolean;
}

export interface PaymentResponse {
  id: string;
  tenantId: string;
  amount: number;
  currency: string;
  method: string;
  status: string;
}

export interface ReceiptResponse {
  id: string;
  paymentId: string;
  receiptNo: string;
  issuedAt: string;
  documentRef: string | null;
}

export interface AiChatResponse {
  answer: string;
  sources: string[];
  generatedAt: string;
  model: string;
  correlationId: string;
}

export interface AttendancePercentageResponse {
  presentOrLate: number;
  total: number;
  percentage: number;
}

export interface JobEligibilityResponse {
  eligible: boolean;
  missingOrInsufficientSkillIds: string[];
}

export interface StudentSkillResponse {
  id: string;
  tenantId: string;
  studentId: string;
  skillId: string;
  proficiencyLevel: string;
  evidenceRef: string | null;
}

export interface AuditLogEntryResponse {
  id: string;
  tenantId: string | null;
  actorUserId: string | null;
  action: string;
  resourceType: string;
  resourceId: string | null;
  createdAt: string;
}

export interface RoleAssignResponse {
  userRoleId: string;
}

export interface SkillCategoryResponse {
  id: string;
  tenantId: string;
  code: string;
  name: string;
  version: number;
}

export interface JobSkillResponse {
  id: string;
  tenantId: string;
  jobId: string;
  skillId: string;
  requiredLevel: string;
}

export interface GuardianGrantResponse {
  grantId: string;
  studentId: string;
  status: string;
  expiresAt: string | null;
  permissions: string[];
}

// --- Faculty (staff) attendance ---

export type StaffAttendanceStatus = 'PRESENT' | 'HALF_DAY' | 'ABSENT' | 'ON_LEAVE';

export interface StaffAttendanceResponse {
  id: string;
  tenantId: string;
  facultyId: string;
  attendanceDate: string;
  status: StaffAttendanceStatus;
  checkInAt: string | null;
  checkOutAt: string | null;
  source: 'SELF' | 'ADMIN';
  correctedBy: string | null;
  correctionReason: string | null;
  /** Minutes after the shift start, only when beyond the grace period; null if on time (or not evaluated). */
  lateMinutes: number | null;
  /** Check-out minus check-in; null until both exist. */
  workedMinutes: number | null;
  /** The policy version this day was judged under (0 = the built-in defaults); null if not evaluated. */
  policyVersion: number | null;
  /** Worked fewer minutes than a half day needs. */
  shortHours: boolean;
}

/** `date` is today in the college's own timezone, supplied by the server along with its clock and the college timezone. */
export interface StaffAttendanceToday {
  date: string;
  serverTime: string;
  timeZone: string;
  record: StaffAttendanceResponse | null;
}

/** A college's staff-attendance rules; times are college-local HH:mm. `version` 0 means the built-in defaults (nothing saved yet). */
export interface StaffAttendancePolicy {
  version: number;
  shiftStart: string;
  shiftEnd: string;
  graceMinutes: number;
  fullDayMinutes: number;
  halfDayMinutes: number;
}

/** A faculty member's request to correct a past day of their own attendance ("I forgot to check in"). */
export interface RegularisationResponse {
  id: string;
  tenantId: string;
  facultyId: string;
  attendanceDate: string;
  requestedStatus: 'PRESENT' | 'HALF_DAY' | 'ON_LEAVE';
  requestedCheckInAt: string | null;
  requestedCheckOutAt: string | null;
  reason: string;
  status: 'PENDING' | 'APPROVED' | 'REJECTED' | 'WITHDRAWN';
  decidedBy: string | null;
  decidedAt: string | null;
  decisionNote: string | null;
  createdAt: string;
}
