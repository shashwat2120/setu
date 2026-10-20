package com.setu.shared.seed;

import com.setu.assessment.application.AssessmentComponentService;
import com.setu.assessment.application.QuestionService;
import com.setu.assessment.domain.QuestionType;
import com.setu.assessment.application.AssessmentService;
import com.setu.assessment.application.GradeService;
import com.setu.attendance.application.AttendanceRecordService;
import com.setu.attendance.application.AttendanceSessionService;
import com.setu.career.application.ApplicationService;
import com.setu.career.application.JobService;
import com.setu.career.application.JobSkillService;
import com.setu.career.application.PlacementRecordService;
import com.setu.career.application.SkillCategoryService;
import com.setu.career.application.SkillService;
import com.setu.career.application.StudentSkillService;
import com.setu.career.domain.ProficiencyLevel;
import com.setu.catalog.application.AcademicPeriodService;
import com.setu.catalog.application.AcademicYearService;
import com.setu.catalog.application.BuildingService;
import com.setu.catalog.application.CourseService;
import com.setu.catalog.application.CurriculumService;
import com.setu.catalog.application.MeetingPatternService;
import com.setu.catalog.application.ProgramService;
import com.setu.catalog.application.RoomService;
import com.setu.catalog.domain.AcademicPeriod;
import com.setu.catalog.domain.CourseStatus;
import com.setu.enrollment.domain.CourseSectionStatus;
import com.setu.directory.application.FacultyProfileService;
import com.setu.directory.application.GuardianProfileService;
import com.setu.directory.application.StudentGuardianService;
import com.setu.directory.application.StudentProfileService;
import com.setu.enrollment.application.CourseSectionService;
import com.setu.enrollment.application.EnrollmentService;
import com.setu.enrollment.application.FacultyAssignmentService;
import com.setu.enrollment.application.StudentProgramService;
import com.setu.enrollment.domain.FacultyAssignmentRole;
import com.setu.finance.application.FeeComponentService;
import com.setu.finance.application.FeeStructureService;
import com.setu.finance.application.PaymentService;
import com.setu.finance.application.StudentChargeService;
import com.setu.guardianaccess.application.GuardianAccessService;
import com.setu.identity.application.PasswordResetService;
import com.setu.identity.application.UserDirectoryService;
import com.setu.identity.application.UserRegistrationService;
import com.setu.identity.domain.RoleCode;
import com.setu.institution.application.AcademicOrganizationService;
import com.setu.institution.application.CampusService;
import com.setu.institution.application.CollegeDirectoryService;
import com.setu.institution.application.DepartmentService;
import com.setu.institution.application.PolicyDocumentService;
import com.setu.learning.application.AssignmentService;
import com.setu.learning.application.AssignmentSubmissionService;
import com.setu.learning.application.CourseModuleService;
import com.setu.learning.application.LearningProgressService;
import com.setu.learning.application.LessonService;
import com.setu.learning.domain.LearningProgressState;
import com.setu.learning.domain.LessonType;
import com.setu.risk.application.InterventionService;
import com.setu.risk.application.RecommendationService;
import com.setu.risk.application.RiskCalculationService;
import com.setu.risk.domain.InterventionStatus;
import com.setu.risk.domain.Recommendation;
import com.setu.risk.domain.RiskScore;
import com.setu.shared.identity.PersonDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Day 15 §demo-data: deterministic demo/seed dataset for manual review and the eventual frontend, per
 * {@code docs/demo-data-plan.md}. Runs only when {@code app.demo-data.enabled=true} (default {@code false} —
 * never in the test suite, never in a real deployment unless explicitly opted into for a local demo).
 * <p>
 * Every row here is created through the SAME real, already-authorized, already-tested application service a
 * genuine HTTP caller would use — never a raw repository insert, never a new "seed-only" code path. The one
 * deliberate authorization exception is {@link UserDirectoryService#grantBootstrapRole}, itself heavily disclosed
 * at its own definition as the sole, narrow, no-auth port this whole dataset needs (the very first role grant a
 * fresh tenant/platform ever gets, which by definition cannot be authorized against a role that doesn't exist
 * yet). Every other authorization-gated call below goes through {@link UserDirectoryService#authenticateForSeed}
 * acting as whichever real seeded principal would actually perform that action (a college admin creates the
 * department, the student themselves scans their own QR code and submits their own assignment, and so on) — so
 * this dataset exercises the exact same authorization pipeline a live reviewer will.
 * <p>
 * Every mutating call below goes through each service's real {@code create}/{@code assign}/... method — never a
 * raw repository insert. Several services also expose a small {@code xxxForSeed} sibling next to that real
 * method (e.g. {@link CampusService#createForSeed}) that does nothing but unwrap the same call's sealed
 * {@code CreateResult} down to a plain {@code UUID} (throwing on any non-success outcome): Spring Modulith does
 * not treat a nested sealed-interface {@code Success} record as part of a class's exposed API even when the
 * class itself is {@code @NamedInterface}, so a cross-module caller pattern-matching on it directly is flagged
 * as a boundary violation. Each {@code xxxForSeed} method's own javadoc points back at the real method it wraps.
 * <p>
 * Student Success data is never hand-set: every {@code risk_scores}/{@code risk_factors}/{@code recommendations}
 * row here is produced by calling {@link RiskCalculationService#recalculateForSeed} against real attendance/grade/
 * submission/progress rows engineered to land each student in a specific, disclosed band — see the per-student
 * comments in {@link #seedTenantAStudents} for the exact arithmetic against {@code RiskScoringEngine}'s real,
 * disclosed weights (attendance 0.30, assessment 0.25, recent-failures 0.15, assignment-completion 0.15,
 * learning-progress 0.15) and band thresholds (HIGH &ge; 60, MEDIUM &ge; 30).
 */
@Component
public class DemoDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    /** Every seeded account's dev-only password — never used outside this class, never a production default. */
    private static final String DEMO_PASSWORD = "SetuDemo#2025!";

    private final UserRegistrationService userRegistrationService;
    private final PasswordResetService passwordResetService;
    private final UserDirectoryService userDirectoryService;
    private final PersonDirectory personDirectory;

    private final CollegeDirectoryService collegeDirectoryService;
    private final CampusService campusService;
    private final AcademicOrganizationService academicOrganizationService;
    private final DepartmentService departmentService;
    private final PolicyDocumentService policyDocumentService;

    private final ProgramService programService;
    private final CurriculumService curriculumService;
    private final com.setu.catalog.application.CurriculumRequirementService curriculumRequirementService;
    private final com.setu.attendance.application.FacultyAttendanceService facultyAttendanceService;
    private final com.setu.attendance.application.FacultyRegularisationService facultyRegularisationService;
    private final com.setu.learning.application.LessonResourceService lessonResourceService;
    private final AcademicYearService academicYearService;
    private final AcademicPeriodService academicPeriodService;
    private final CourseService courseService;
    private final BuildingService buildingService;
    private final RoomService roomService;
    private final MeetingPatternService meetingPatternService;

    private final CourseSectionService courseSectionService;
    private final FacultyAssignmentService facultyAssignmentService;
    private final StudentProgramService studentProgramService;
    private final EnrollmentService enrollmentService;

    private final StudentProfileService studentProfileService;
    private final FacultyProfileService facultyProfileService;
    private final GuardianProfileService guardianProfileService;
    private final StudentGuardianService studentGuardianService;
    private final GuardianAccessService guardianAccessService;

    private final AttendanceSessionService attendanceSessionService;
    private final AttendanceRecordService attendanceRecordService;

    private final CourseModuleService courseModuleService;
    private final LessonService lessonService;
    private final AssignmentService assignmentService;
    private final AssignmentSubmissionService assignmentSubmissionService;
    private final LearningProgressService learningProgressService;

    private final AssessmentService assessmentService;
    private final AssessmentComponentService assessmentComponentService;
    private final QuestionService questionService;
    private final GradeService gradeService;

    private final RiskCalculationService riskCalculationService;
    private final RecommendationService recommendationService;
    private final InterventionService interventionService;

    private final FeeStructureService feeStructureService;
    private final FeeComponentService feeComponentService;
    private final StudentChargeService studentChargeService;
    private final PaymentService paymentService;

    private final SkillCategoryService skillCategoryService;
    private final SkillService skillService;
    private final StudentSkillService studentSkillService;
    private final JobService jobService;
    private final JobSkillService jobSkillService;
    private final ApplicationService applicationService;
    private final PlacementRecordService placementRecordService;

    @Value("${app.demo-data.enabled:false}")
    private boolean enabled;

    /**
     * Blank by default (every seeded address stays {@code @demo.setu.local}, exactly as before — no behavior
     * change for {@code mvn test} or CI). Set only via the gitignored {@code backend/.env.local}'s
     * {@code DEMO_EMAIL_BASE} for an interactive local demo/review run that needs forgot-password to deliver to
     * a real inbox: every seeded account's email becomes {@code localPart+tag@domain} of this base address
     * (Gmail-style "+" aliasing), so all of them route to the SAME real mailbox while staying distinct rows
     * under the database's unique-email constraint. Never a literal personal address in source.
     */
    @Value("${app.demo-data.email-base:}")
    private String emailBase;

    /** See {@link #emailBase}'s own javadoc. {@code tag} reuses the same dot-separated local-part every
     * {@code @demo.setu.local} address has always used (e.g. {@code "student.strong"}) so the alias stays
     * readable and collision-free. */
    private String demoEmail(String tag) {
        if (emailBase == null || emailBase.isBlank()) {
            return tag + "@demo.setu.local";
        }
        int at = emailBase.indexOf('@');
        if (at < 0) {
            throw new IllegalStateException("app.demo-data.email-base (DEMO_EMAIL_BASE) is not a valid email: " + emailBase);
        }
        return emailBase.substring(0, at) + "+" + tag + emailBase.substring(at);
    }

    /**
     * {@code institution.colleges.code} carries a genuinely GLOBAL unique constraint ({@code uq_colleges_code},
     * V6) — unlike every other seeded code below it (department/course/building/etc.), which is scoped to its
     * parent college and therefore can never collide across separate seeder runs, since each run creates its
     * own fresh college row. Only this one needs the same "vary when a custom base is set" treatment as {@link
     * #demoEmail}, so a {@code DEMO_EMAIL_BASE}-configured run can coexist in the same database as a default
     * ({@code @demo.setu.local}) run — e.g. both {@code DemoDataSeederVerificationTests} and this class's own
     * email-aliasing test in one {@code mvn test} invocation — without a real, unrelated {@code uq_colleges_code}
     * collision masking as a test failure.
     */
    private String demoCode(String code) {
        // the suffix comes from the base itself, so two runs with different bases (the aliasing test and the restart test) can share a database,
        // and the same base always gives the same code
        return (emailBase == null || emailBase.isBlank()) ? code
            : code + "-" + Integer.toHexString(emailBase.trim().toLowerCase().hashCode()).toUpperCase();
    }

    public DemoDataSeeder(UserRegistrationService userRegistrationService, PasswordResetService passwordResetService,
                           UserDirectoryService userDirectoryService, PersonDirectory personDirectory,
                           CollegeDirectoryService collegeDirectoryService, CampusService campusService,
                           AcademicOrganizationService academicOrganizationService, DepartmentService departmentService,
                           PolicyDocumentService policyDocumentService, ProgramService programService,
                           CurriculumService curriculumService, AcademicYearService academicYearService,
                           AcademicPeriodService academicPeriodService, CourseService courseService,
                           BuildingService buildingService, RoomService roomService,
                           MeetingPatternService meetingPatternService, CourseSectionService courseSectionService,
                           FacultyAssignmentService facultyAssignmentService, StudentProgramService studentProgramService,
                           EnrollmentService enrollmentService, StudentProfileService studentProfileService,
                           FacultyProfileService facultyProfileService, GuardianProfileService guardianProfileService,
                           StudentGuardianService studentGuardianService, GuardianAccessService guardianAccessService,
                           AttendanceSessionService attendanceSessionService, AttendanceRecordService attendanceRecordService,
                           CourseModuleService courseModuleService, LessonService lessonService,
                           AssignmentService assignmentService, AssignmentSubmissionService assignmentSubmissionService,
                           LearningProgressService learningProgressService, AssessmentService assessmentService,
                           AssessmentComponentService assessmentComponentService, QuestionService questionService, GradeService gradeService,
                           RiskCalculationService riskCalculationService, RecommendationService recommendationService,
                           InterventionService interventionService, FeeStructureService feeStructureService,
                           FeeComponentService feeComponentService, StudentChargeService studentChargeService,
                           PaymentService paymentService, SkillCategoryService skillCategoryService,
                           SkillService skillService, StudentSkillService studentSkillService, JobService jobService,
                           JobSkillService jobSkillService, ApplicationService applicationService,
                           PlacementRecordService placementRecordService,
                           com.setu.catalog.application.CurriculumRequirementService curriculumRequirementService,
                           com.setu.attendance.application.FacultyAttendanceService facultyAttendanceService,
                           com.setu.attendance.application.FacultyRegularisationService facultyRegularisationService,
                           com.setu.learning.application.LessonResourceService lessonResourceService,
                           com.setu.shared.governance.AuditLogWriter auditLogWriter, org.springframework.jdbc.core.simple.JdbcClient jdbc) {
        this.auditLogWriter = auditLogWriter;
        this.jdbc = jdbc;
        this.facultyRegularisationService = facultyRegularisationService;
        this.lessonResourceService = lessonResourceService;
        this.curriculumRequirementService = curriculumRequirementService;
        this.facultyAttendanceService = facultyAttendanceService;
        this.userRegistrationService = userRegistrationService;
        this.passwordResetService = passwordResetService;
        this.userDirectoryService = userDirectoryService;
        this.personDirectory = personDirectory;
        this.collegeDirectoryService = collegeDirectoryService;
        this.campusService = campusService;
        this.academicOrganizationService = academicOrganizationService;
        this.departmentService = departmentService;
        this.policyDocumentService = policyDocumentService;
        this.programService = programService;
        this.curriculumService = curriculumService;
        this.academicYearService = academicYearService;
        this.academicPeriodService = academicPeriodService;
        this.courseService = courseService;
        this.buildingService = buildingService;
        this.roomService = roomService;
        this.meetingPatternService = meetingPatternService;
        this.courseSectionService = courseSectionService;
        this.facultyAssignmentService = facultyAssignmentService;
        this.studentProgramService = studentProgramService;
        this.enrollmentService = enrollmentService;
        this.studentProfileService = studentProfileService;
        this.facultyProfileService = facultyProfileService;
        this.guardianProfileService = guardianProfileService;
        this.studentGuardianService = studentGuardianService;
        this.guardianAccessService = guardianAccessService;
        this.attendanceSessionService = attendanceSessionService;
        this.attendanceRecordService = attendanceRecordService;
        this.courseModuleService = courseModuleService;
        this.lessonService = lessonService;
        this.assignmentService = assignmentService;
        this.assignmentSubmissionService = assignmentSubmissionService;
        this.learningProgressService = learningProgressService;
        this.assessmentService = assessmentService;
        this.assessmentComponentService = assessmentComponentService;
        this.questionService = questionService;
        this.gradeService = gradeService;
        this.riskCalculationService = riskCalculationService;
        this.recommendationService = recommendationService;
        this.interventionService = interventionService;
        this.feeStructureService = feeStructureService;
        this.feeComponentService = feeComponentService;
        this.studentChargeService = studentChargeService;
        this.paymentService = paymentService;
        this.skillCategoryService = skillCategoryService;
        this.skillService = skillService;
        this.studentSkillService = studentSkillService;
        this.jobService = jobService;
        this.jobSkillService = jobSkillService;
        this.applicationService = applicationService;
        this.placementRecordService = placementRecordService;
    }

    private final com.setu.shared.governance.AuditLogWriter auditLogWriter;
    private final org.springframework.jdbc.core.simple.JdbcClient jdbc;

    /** The audit entry written when seeding has finished: what a restart looks for to know there is nothing to do. */
    static final String SEEDED_MARKER = "DEMO_DATA_SEEDED";

    private record Account(UUID userId, String email) {
    }

    /** One student's full identity chain: login account, directory person, and directory student profile. */
    private record SeededStudent(String label, Account account, UUID personId, UUID studentId) {
    }

    private record SeededFaculty(Account account, UUID personId, UUID facultyId) {
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            return;
        }
        String superAdminEmail = demoEmail("super.admin");
        if (seedingFinishedFor(superAdminEmail)) {
            // A restart of a service whose database outlives it (a container, a persistent volume) must not die on its own data.
            log.info("=== Setu demo data seeding: skipped, it has already been done for {} ===", superAdminEmail);
            return;
        }
        log.info("=== Setu demo data seeding: starting ===");
        try {
            List<String> credentials = new ArrayList<>();
            Account superAdmin = seedSuperAdmin(credentials);
            seedTenantA(superAdmin, credentials);
            seedTenantB(credentials);

            auditLogWriter.write(null, null, SEEDED_MARKER, "demo-data", null, java.util.Map.of("superAdmin", superAdminEmail));
            log.info("=== Setu demo data seeding: complete ===");
            log.info("--- Demo credentials (dev-only, never real accounts) ---");
            credentials.forEach(log::info);
        } finally {
            userDirectoryService.clearSeedAuthentication();
        }
    }

    /**
     * Whether an earlier run finished seeding this exact demo (the same super admin address). Seeding is many transactions, not one, so "the
     * super admin exists" cannot mean "done": a run that died half way would be taken for a finished one. Only the marker written at the very
     * end counts. Data without a marker (an earlier run that did not finish) is left to fail loudly, as before: start from a clean database.
     */
    private boolean seedingFinishedFor(String superAdminEmail) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM governance.audit_logs WHERE action = :action AND after_json ->> 'superAdmin' = :email)")
            .param("action", SEEDED_MARKER).param("email", superAdminEmail).query(Boolean.class).single();
    }

    // ---------------------------------------------------------------- platform bootstrap

    private Account seedSuperAdmin(List<String> credentials) {
        String email = demoEmail("super.admin");
        UUID userId = registerAndPassword(email, credentials, "SUPER_ADMIN (platform)");
        userDirectoryService.grantBootstrapRole(userId, RoleCode.SUPER_ADMIN, null);
        // Real gap found live: with no PersonDirectory entry, CurrentUserController's /me returns a null
        // fullName and the header falls back to a generic "Account" label instead of a real name — the same
        // gap college admins and the placement officer had below, fixed the same way.
        personDirectory.createPerson(userId, "Neha Deshpande", "Neha", LocalDate.of(1980, 2, 18), "UNSPECIFIED",
            email, "+91-9800000201", null);
        return new Account(userId, email);
    }

    private UUID registerAndPassword(String email, List<String> credentials, String roleLabel) {
        UUID userId = userRegistrationService.registerForSeed(email);
        String rawToken = passwordResetService.initiate(email).orElseThrow();
        var result = passwordResetService.complete(rawToken, DEMO_PASSWORD);
        if (result != PasswordResetService.CompletionResult.SUCCESS) {
            throw new IllegalStateException("failed to set demo password for " + email);
        }
        credentials.add(roleLabel + " | " + email + " | " + DEMO_PASSWORD);
        return userId;
    }

    // ---------------------------------------------------------------- Tenant A: Setu Institute of Technology

    private void seedTenantA(Account superAdmin, List<String> credentials) {
        UUID tenantId = collegeDirectoryService.provisionCollege(demoCode("SIT"), "Setu Institute of Technology", "Asia/Kolkata");
        log.info("Tenant A provisioned: Setu Institute of Technology ({})", tenantId);

        String collegeAdminEmail = demoEmail("admin.sit");
        Account collegeAdmin = new Account(
            registerAndPassword(collegeAdminEmail, credentials, "COLLEGE_ADMIN (Setu Institute of Technology)"),
            collegeAdminEmail);
        userDirectoryService.grantBootstrapRole(collegeAdmin.userId(), RoleCode.COLLEGE_ADMIN, tenantId);
        personDirectory.createPerson(collegeAdmin.userId(), "Rohan Kulkarni", "Rohan", LocalDate.of(1982, 7, 9),
            "UNSPECIFIED", collegeAdminEmail, "+91-9800000202", null);

        userDirectoryService.authenticateForSeed(collegeAdmin.userId());

        UUID campusId = campusService.createForSeed(tenantId, "MAIN", "Main Campus");
        UUID orgId = academicOrganizationService.createForSeed(campusId, null, "School of Computing", "SCHOOL");
        UUID deptId = departmentService.createForSeed(orgId, "CSE", "Computer Science & Engineering");
        UUID programId = programService.createForSeed(deptId, "BTECH-CSE", "B.Tech Computer Science & Engineering",
            "BACHELOR", 8, new BigDecimal("160"));
        UUID curriculumId = curriculumService.createReferenceForSeed(programId, "B.Tech CSE 2024 Curriculum", "v2024",
            LocalDate.of(2024, 6, 1), null);
        // Degree requirements that add up to the program's 160 credits, so Degree Progress reads "0 of 160" rather than "0 of 0".
        // Seed data, like everything here: nobody has COMPLETED a course yet, so progress is honestly 0% until enrollments complete.
        curriculumRequirementService.createForSeed(curriculumId, "CORE", new BigDecimal("100"), 25, null);
        curriculumRequirementService.createForSeed(curriculumId, "ELECTIVE", new BigDecimal("36"), 9, null);
        curriculumRequirementService.createForSeed(curriculumId, "GENERAL_EDUCATION", new BigDecimal("24"), 6, null);

        UUID yearId = academicYearService.createYear(tenantId, "AY2024-25", LocalDate.of(2024, 6, 1), LocalDate.of(2025, 5, 31));
        academicYearService.markCurrent(yearId);

        Instant regOpen = Instant.now().minusSeconds(86400L * 60).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        Instant regClose = Instant.now().minusSeconds(86400L * 30).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        UUID periodId = academicPeriodService.createPeriodForSeed(yearId, "SEM1", "Semester 1", 1,
            LocalDate.of(2024, 6, 1), LocalDate.of(2024, 11, 30), regOpen, regClose);
        academicPeriodService.transitionTo(periodId, AcademicPeriod.PeriodStatus.REGISTRATION_OPEN);

        UUID course1Id = courseService.createForSeed(deptId, "CS101", "Introduction to Programming",
            new BigDecimal("4"), "CORE", "UG", "Foundations of programming using Java.");
        courseService.changeStatus(course1Id, CourseStatus.ACTIVE);
        UUID course2Id = courseService.createForSeed(deptId, "CS201", "Data Structures", new BigDecimal("4"),
            "CORE", "UG", "Core data structures and algorithmic complexity.");
        courseService.changeStatus(course2Id, CourseStatus.ACTIVE);

        UUID buildingId = buildingService.createForSeed(tenantId, "BLK-A", "Academic Block A");
        UUID room1Id = roomService.createForSeed(buildingId, "A101", 60, "{\"projector\":true}");
        UUID room2Id = roomService.createForSeed(buildingId, "A102", 60, "{\"projector\":true}");

        UUID section1Id = courseSectionService.createForSeed(course1Id, periodId, null, "CS101-A", 10, room1Id, regOpen, regClose);
        UUID section2Id = courseSectionService.createForSeed(course2Id, periodId, null, "CS201-A", 10, room2Id, regOpen, regClose);
        // A section must be OPEN before enrollment.request() will accept it (EnrollmentService's own
        // SECTION_NOT_OPEN gate) — real bug this session's risk-data verification found: without this, every
        // seeded enrollment request below silently landed as REJECTED, leaving every student with zero ENROLLED
        // enrollments and therefore a uniform, undifferentiated LOW risk band regardless of the engineered profile.
        courseSectionService.changeStatus(section1Id, CourseSectionStatus.OPEN);
        courseSectionService.changeStatus(section2Id, CourseSectionStatus.OPEN);

        UUID meeting1Id = meetingPatternService.createForSeed(section1Id, (short) 1, LocalTime.of(9, 0), LocalTime.of(10, 0),
            room1Id, LocalDate.of(2024, 6, 1), null);
        UUID meeting2Id = meetingPatternService.createForSeed(section2Id, (short) 3, LocalTime.of(10, 0), LocalTime.of(11, 0), room2Id,
            LocalDate.of(2024, 6, 1), null);

        seedPolicyDocuments(tenantId);

        SeededFaculty faculty1 = seedFaculty(tenantId, demoEmail("faculty.rao"), "Ananya Rao", "EMP-SIT-001",
            "Assistant Professor", collegeAdmin.userId(), credentials);
        SeededFaculty faculty2 = seedFaculty(tenantId, demoEmail("faculty.mehta"), "Vikram Mehta", "EMP-SIT-002",
            "Associate Professor", collegeAdmin.userId(), credentials);
        userDirectoryService.authenticateForSeed(collegeAdmin.userId());
        facultyAssignmentService.assign(section1Id, faculty1.facultyId(), FacultyAssignmentRole.PRIMARY,
            new BigDecimal("100"), LocalDate.of(2024, 6, 1), null);
        facultyAssignmentService.assign(section2Id, faculty2.facultyId(), FacultyAssignmentRole.PRIMARY,
            new BigDecimal("100"), LocalDate.of(2024, 6, 1), null);

        List<SeededStudent> students = seedTenantAStudents(tenantId, collegeAdmin, faculty1.account().userId(),
            faculty2.account().userId(), programId, curriculumId, section1Id, section2Id, meeting1Id, meeting2Id, credentials);

        seedStaffAttendance(collegeAdmin, faculty1, faculty2);
        seedTenantAGuardians(tenantId, collegeAdmin, students, credentials);

        String placementOfficerEmail = demoEmail("placement.officer");
        UUID placementOfficerId = registerAndPassword(placementOfficerEmail, credentials,
            "PLACEMENT_OFFICER (Setu Institute of Technology)");
        userDirectoryService.grantBootstrapRole(placementOfficerId, RoleCode.PLACEMENT_OFFICER, tenantId);
        personDirectory.createPerson(placementOfficerId, "Karan Malhotra", "Karan", LocalDate.of(1987, 11, 23),
            "UNSPECIFIED", placementOfficerEmail, "+91-9800000203", null);

        seedTenantACareer(tenantId, new Account(placementOfficerId, placementOfficerEmail), students, credentials);
        seedTenantAFinance(tenantId, collegeAdmin, programId, yearId, students, credentials);

        userDirectoryService.authenticateForSeed(superAdmin.userId());
    }

    /**
     * Example staff attendance for the previous weekdays, so the admin Staff Attendance page and the faculty history are not
     * empty on first run. Explicitly SEED DATA: every row is an administrator mark (the only way a past day can be recorded)
     * carrying a reason that says so. Today is deliberately left unrecorded so the live check-in demo works.
     */
    private void seedStaffAttendance(Account collegeAdmin, SeededFaculty faculty1, SeededFaculty faculty2) {
        userDirectoryService.authenticateForSeed(collegeAdmin.userId());
        LocalDate today = facultyAttendanceService.todayForCurrentTenant().orElse(LocalDate.now());
        String reason = "Demo seed data (example): recorded from the attendance register";
        int weekdaysSeeded = 0;
        LocalDate absentDay = null;
        for (int back = 1; back <= 14 && weekdaysSeeded < 8; back++) {
            LocalDate day = today.minusDays(back);
            if (day.getDayOfWeek() == java.time.DayOfWeek.SATURDAY || day.getDayOfWeek() == java.time.DayOfWeek.SUNDAY) {
                continue;
            }
            weekdaysSeeded++;
            var status1 = weekdaysSeeded == 3 ? com.setu.attendance.domain.FacultyAttendanceStatus.ON_LEAVE
                : com.setu.attendance.domain.FacultyAttendanceStatus.PRESENT;
            var status2 = weekdaysSeeded == 5 ? com.setu.attendance.domain.FacultyAttendanceStatus.HALF_DAY
                : weekdaysSeeded == 7 ? com.setu.attendance.domain.FacultyAttendanceStatus.ABSENT
                : com.setu.attendance.domain.FacultyAttendanceStatus.PRESENT;
            facultyAttendanceService.markByAdmin(faculty1.facultyId(), day, status1, reason);
            facultyAttendanceService.markByAdmin(faculty2.facultyId(), day, status2, reason);
            if (status2 == com.setu.attendance.domain.FacultyAttendanceStatus.ABSENT) {
                absentDay = day;
            }
        }
        // One example request waiting in the admin queue: faculty2 was recorded ABSENT that day and says they forgot to check in.
        if (absentDay != null) {
            userDirectoryService.authenticateForSeed(faculty2.account().userId());
            facultyRegularisationService.request(absentDay, com.setu.attendance.domain.FacultyAttendanceStatus.PRESENT,
                java.time.LocalTime.of(9, 30), java.time.LocalTime.of(17, 0),
                "Demo seed data (example): forgot to check in; I was teaching CS201-A that day");
            userDirectoryService.authenticateForSeed(collegeAdmin.userId());
        }
    }

    /**
     * Real questions for a seeded quiz, added while the assessment is still a DRAFT (questions cannot be added later). Three questions
     * per quiz, one of each auto-gradable kind, worth 40 + 30 + 30 points. A multiple-choice question carries its options in the
     * prompt and is answered by letter, because students never receive the answer key (the grader reads {@code correctOption},
     * {@code correctAnswer}).
     */
    private void seedQuizQuestions(UUID componentId, String topic) {
        String[][] questions = switch (topic) {
            case "PYTHON" -> new String[][]{
                {"MCQ_SINGLE", "40", "Which keyword defines a function in Python?  A) func   B) def   C) function   D) lambda only", "{\"correctOption\":\"B\"}"},
                {"TRUE_FALSE", "30", "In Python, the characters of a string can be changed in place after the string is created.", "{\"correctOption\":\"False\"}"},
                {"SHORT_ANSWER", "30", "Which built-in function returns the number of items in a list?", "{\"correctAnswer\":\"len\"}"}};
            case "PYTHON_FINAL" -> new String[][]{
                {"MCQ_SINGLE", "40", "What does the expression [x * 2 for x in range(3)] evaluate to in Python?  A) [0, 2, 4]   B) [2, 4, 6]   C) [0, 1, 2]   D) (0, 2, 4)", "{\"correctOption\":\"A\"}"},
                {"TRUE_FALSE", "30", "A Python dictionary can use a list as one of its keys.", "{\"correctOption\":\"False\"}"},
                {"SHORT_ANSWER", "30", "Which keyword starts a block that runs whether or not an exception was raised?", "{\"correctAnswer\":\"finally\"}"}};
            case "DATA_STRUCTURES" -> new String[][]{
                {"MCQ_SINGLE", "40", "What is the time complexity of binary search on a sorted array?  A) O(n)   B) O(log n)   C) O(n log n)   D) O(1)", "{\"correctOption\":\"B\"}"},
                {"TRUE_FALSE", "30", "A stack removes the most recently added item first.", "{\"correctOption\":\"True\"}"},
                {"SHORT_ANSWER", "30", "Which data structure serves items in first-in, first-out order?", "{\"correctAnswer\":\"queue\"}"}};
            case "READING" -> new String[][]{
                {"MCQ_SINGLE", "40", "What does a close reading examine first?  A) the author's biography   B) the words and structure on the page   C) the publisher   D) the length of the text", "{\"correctOption\":\"B\"}"},
                {"TRUE_FALSE", "30", "A thesis statement should be supported by evidence from the text.", "{\"correctOption\":\"True\"}"},
                {"SHORT_ANSWER", "30", "Which figure of speech compares two unlike things using the word like or as?", "{\"correctAnswer\":\"simile\"}"}};
            default -> throw new IllegalArgumentException("unknown quiz topic: " + topic);
        };
        for (String[] question : questions) {
            questionService.createForSeed(componentId, QuestionType.valueOf(question[0]), question[2], new BigDecimal(question[1]), question[3]);
        }
    }

    /** Attaches one example document/link to a seeded lesson; any refusal is a seed bug and fails loudly (see addForSeed). */
    private void attachResource(UUID lessonId, com.setu.learning.domain.LessonResourceType type, String url, String title) {
        lessonResourceService.addForSeed(lessonId, url, type, title);
    }

    private void seedPolicyDocuments(UUID tenantId) {
        policyDocumentService.create(tenantId, "Attendance Policy",
            "Students must maintain at least 75% attendance in every enrolled section to remain eligible to "
                + "sit an end-of-term assessment. Attendance below this threshold requires a documented, "
                + "college-admin-approved exception before the assessment period opens.", null);
        policyDocumentService.create(tenantId, "Grading Policy",
            "Grades are published per assessment component and become the student's current grade for that "
                + "component immediately on publish. A later correction supersedes the prior grade but never "
                + "overwrites it, and the full history remains visible to the student and their advisor.", null);
        policyDocumentService.create(tenantId, "Guardian Delegated Access Policy",
            "A guardian relationship on its own never grants a guardian any access to a student's records. "
                + "Access requires a separate, explicit, scoped grant naming exactly which permissions apply and "
                + "an optional expiry, and any grant can be revoked by a college admin at any time with immediate "
                + "effect.", "VIEW_ACADEMIC_STATUS");
    }

    private SeededFaculty seedFaculty(UUID tenantId, String email, String fullName, String employeeNo,
                                       String designation, UUID collegeAdminUserId, List<String> credentials) {
        UUID userId = registerAndPassword(email, credentials, "FACULTY (" + fullName + ")");
        userDirectoryService.grantBootstrapRole(userId, RoleCode.FACULTY, tenantId);
        userDirectoryService.authenticateForSeed(collegeAdminUserId);
        UUID personId = personDirectory.createPerson(userId, fullName, fullName.split(" ")[0],
            LocalDate.of(1985, 4, 12), "UNSPECIFIED", email, "+91-9800000001", null);
        UUID facultyId = facultyProfileService.createForSeed(tenantId, personId, employeeNo, designation, LocalDate.of(2024, 6, 1));
        return new SeededFaculty(new Account(userId, email), personId, facultyId);
    }

    private SeededStudent admitStudent(UUID tenantId, UUID collegeAdminUserId, String email, String fullName,
                                        String admissionNo, String label, List<String> credentials) {
        UUID userId = registerAndPassword(email, credentials, "STUDENT [" + label + "] (" + fullName + ")");
        userDirectoryService.grantBootstrapRole(userId, RoleCode.STUDENT, tenantId);
        userDirectoryService.authenticateForSeed(collegeAdminUserId);
        UUID personId = personDirectory.createPerson(userId, fullName, fullName.split(" ")[0],
            LocalDate.of(2005, 8, 20), "UNSPECIFIED", email, "+91-9800000002", null);
        UUID studentId = studentProfileService.admitStudentForSeed(tenantId, personId, admissionNo,
            admissionNo.replace("ADM", "ROLL"), LocalDate.of(2024, 6, 1));
        return new SeededStudent(label, new Account(userId, email), personId, studentId);
    }

    /**
     * Six students, each landing in a specific, real, RiskScoringEngine-derived band by construction — never a
     * hand-set {@code risk_scores} row. The arithmetic below is checked against the engine's actual, disclosed
     * weights (ATTENDANCE 0.30, ASSESSMENT 0.25, RECENT_FAILURES 0.15, ASSIGNMENT_COMPLETION 0.15,
     * LEARNING_PROGRESS 0.15) and thresholds (safe attendance &ge;75%, safe grade point &ge;2.5, failure cap 3,
     * HIGH band &ge;60, MEDIUM band &ge;30):
     * <ul>
     * <li>STRONG — 100% attendance, gradePoint 3.8, 0 failures, 0% late, 100% learning-progress completion
     * &rarr; score 0 &rarr; LOW, every factor at zero.</li>
     * <li>AVERAGE — 60% attendance (+6.0), gradePoint 2.0 (+5.0), 0 failures, 50% late (+7.5), 50% progress
     * (+7.5) &rarr; score 26 &rarr; LOW but visibly non-zero across every factor (no single dominant cause).</li>
     * <li>ATTENDANCE-RISK — 0% attendance (+30.0), everything else clean &rarr; score ~30 &rarr; MEDIUM, ~100%
     * of the score from ATTENDANCE_TREND alone.</li>
     * <li>ASSESSMENT-RISK — gradePoint 0.0/"F" (+25.0) and the resulting 1 recent failure (+5.0), attendance and
     * engagement clean &rarr; score 30 &rarr; MEDIUM, driven entirely by ASSESSMENT_PERFORMANCE + RECENT_FAILURES.</li>
     * <li>LEARNING-PROGRESS-RISK — 100% late submissions (+15.0) and 0% lesson completion (+15.0), attendance
     * and grades clean &rarr; score 30 &rarr; MEDIUM, driven entirely by LEARNING_PROGRESS + ASSIGNMENT_COMPLETION.</li>
     * <li>INTERVENTION — every factor stacked at its worst (0% attendance, "F" grade, 100% late, 0% progress)
     * &rarr; score ~90 &rarr; HIGH, which auto-proposes a RULE recommendation this method then accepts, creating
     * a real, linked {@code interventions} row.</li>
     * </ul>
     */
    private List<SeededStudent> seedTenantAStudents(UUID tenantId, Account collegeAdmin, UUID faculty1UserId, UUID faculty2UserId,
                                                      UUID programId, UUID curriculumId, UUID section1Id, UUID section2Id,
                                                      UUID meeting1Id, UUID meeting2Id, List<String> credentials) {
        SeededStudent strong = admitStudent(tenantId, collegeAdmin.userId(), demoEmail("student.strong"),
            "Ishaan Kapoor", "ADM-SIT-1001", "STRONG", credentials);
        SeededStudent average = admitStudent(tenantId, collegeAdmin.userId(), demoEmail("student.average"),
            "Priya Nair", "ADM-SIT-1002", "AVERAGE", credentials);
        SeededStudent attendanceRisk = admitStudent(tenantId, collegeAdmin.userId(), demoEmail("student.attendance.risk"),
            "Rahul Verma", "ADM-SIT-1003", "ATTENDANCE-RISK", credentials);
        SeededStudent assessmentRisk = admitStudent(tenantId, collegeAdmin.userId(), demoEmail("student.assessment.risk"),
            "Sneha Iyer", "ADM-SIT-1004", "ASSESSMENT-RISK", credentials);
        SeededStudent learningRisk = admitStudent(tenantId, collegeAdmin.userId(), demoEmail("student.learning.risk"),
            "Arjun Singh", "ADM-SIT-1005", "LEARNING-PROGRESS-RISK", credentials);
        SeededStudent intervention = admitStudent(tenantId, collegeAdmin.userId(), demoEmail("student.intervention"),
            "Meera Joshi", "ADM-SIT-1006", "INTERVENTION", credentials);

        List<SeededStudent> all = List.of(strong, average, attendanceRisk, assessmentRisk, learningRisk, intervention);

        userDirectoryService.authenticateForSeed(collegeAdmin.userId());
        for (SeededStudent s : all) {
            // Three batches, so a job limited to one batch has someone to accept and someone to refuse: Ishaan is in his final year (the batch of 2027, admitted 2023),
            // Meera in her second (2029, admitted 2025), the rest in their third (2028, admitted 2024). Only the admission year differs; every other record is the same.
            int admissionYear = s == strong ? 2023 : s == intervention ? 2025 : 2024;
            studentProgramService.assign(s.studentId(), programId, curriculumId, admissionYear, true, LocalDate.of(2024, 6, 1));
        }
        for (SeededStudent s : all) {
            userDirectoryService.authenticateForSeed(s.account().userId());
            enrollmentService.request(s.studentId(), section1Id);
        }
        // Only the first three also take CS201-A — gives faculty2 (CS201-A's own primary instructor) a distinct
        // advisee set from faculty1 (CS101-A only), demonstrating the section-scoped visibility boundary itself.
        for (SeededStudent s : List.of(strong, average, attendanceRisk)) {
            userDirectoryService.authenticateForSeed(s.account().userId());
            enrollmentService.request(s.studentId(), section2Id);
        }

        userDirectoryService.authenticateForSeed(collegeAdmin.userId());
        UUID moduleId = courseModuleService.createForSeed(section1Id, "Module 1: Foundations", 1);
        // Real, public learning material (verified to exist when this seed was written): official CS50P lectures and notes, the Python
        // documentation, and a free textbook PDF. Every lesson's video is a YouTube address; every attachment is a REFERENCE (a link) —
        // no file is stored here. Example content: a college would replace these with its own.
        UUID lesson1Id = lessonService.createForSeed(moduleId, "Variables & Types", LessonType.VIDEO,
            "https://www.youtube.com/watch?v=JP7ITIXGpHk", 1, null);
        attachResource(lesson1Id, com.setu.learning.domain.LessonResourceType.DOCUMENT, "https://cs50.harvard.edu/python/2022/notes/0/",
            "CS50P Lecture 0 notes: functions and variables (Harvard CS50)");
        attachResource(lesson1Id, com.setu.learning.domain.LessonResourceType.DOCUMENT, "https://docs.python.org/3/tutorial/introduction.html",
            "The Python Tutorial: an informal introduction (python.org)");
        attachResource(lesson1Id, com.setu.learning.domain.LessonResourceType.DOCUMENT, "https://greenteapress.com/thinkpython2/thinkpython2.pdf",
            "Think Python, 2nd edition (free PDF, Allen B. Downey)");
        UUID lesson2Id = lessonService.createForSeed(moduleId, "Control Flow", LessonType.VIDEO,
            "https://www.youtube.com/watch?v=_b6NgY_pMdw", 2, null);
        attachResource(lesson2Id, com.setu.learning.domain.LessonResourceType.DOCUMENT, "https://cs50.harvard.edu/python/notes/1/",
            "CS50P Lecture 1 notes: conditionals (Harvard CS50)");
        attachResource(lesson2Id, com.setu.learning.domain.LessonResourceType.DOCUMENT, "https://cs50.harvard.edu/python/notes/2/",
            "CS50P Lecture 2 notes: loops (Harvard CS50)");
        attachResource(lesson2Id, com.setu.learning.domain.LessonResourceType.DOCUMENT, "https://docs.python.org/3/tutorial/controlflow.html",
            "The Python Tutorial: more control flow tools (python.org)");
        // CS101 stays at exactly two lessons: seeded learning progress feeds the risk engine ("completion vs expected"), so adding a lesson
        // here would silently change every seeded risk result. A second walkthrough therefore rides along as a supplementary video resource.
        attachResource(lesson1Id, com.setu.learning.domain.LessonResourceType.VIDEO, "https://www.youtube.com/watch?v=kqtD5dpn9C8",
            "Python in one hour: a second walkthrough (Programming with Mosh)");

        // MANAGE_ASSIGNMENTS has no tenant-wide "_ALL_SECTIONS" variant (confirmed against identity.role_permissions:
        // only SUPER_ADMIN holds it outright) — assignment ownership is section-faculty-or-platform-admin only, never
        // college admin. Authenticating as faculty1 (assigned PRIMARY to section1Id) satisfies the same
        // assigned-faculty bypass every real instructor creating their own assignment would rely on.
        userDirectoryService.authenticateForSeed(faculty1UserId);
        UUID onTimeAssignmentId = assignmentService.createForSeed(section1Id, "A1 - Problem Set", new BigDecimal("100"),
            Instant.now().plusSeconds(86400L * 30), null);
        assignmentService.publish(onTimeAssignmentId);
        UUID lateAssignmentId = assignmentService.createForSeed(section1Id, "A2 - Lab Report", new BigDecimal("100"),
            Instant.now().minusSeconds(60), null);
        assignmentService.publish(lateAssignmentId);

        userDirectoryService.authenticateForSeed(collegeAdmin.userId());
        UUID assessmentId = assessmentService.createForSeed(section1Id, "QUIZ", "Midterm Quiz", new BigDecimal("100"),
            60, Instant.now().minusSeconds(3600), Instant.now().plusSeconds(3600));
        UUID componentId = assessmentComponentService.createForSeed(assessmentId, "MAIN", new BigDecimal("100"),
            new BigDecimal("100"), 1);
        seedQuizQuestions(componentId, "PYTHON");
        assessmentService.schedule(assessmentId);
        assessmentService.open(assessmentId);

        // CS101-A's final exam: open, no grade published. Its component is the course's final grade, so publishing a student's grade on
        // it is what completes that student's enrollment (the demo's "course ends" step); until someone does, nothing about the seeded
        // risk story changes, because risk reads published grades only.
        UUID finalExamId = assessmentService.createForSeed(section1Id, "EXAM", "Final Exam", new BigDecimal("100"),
            90, Instant.now().minusSeconds(3600), Instant.now().plusSeconds(86400L * 30));
        UUID finalExamComponentId = assessmentComponentService.createFinalGradeForSeed(finalExamId, "FINAL", new BigDecimal("100"),
            new BigDecimal("100"), 1);
        seedQuizQuestions(finalExamComponentId, "PYTHON_FINAL");
        assessmentService.schedule(finalExamId);
        assessmentService.open(finalExamId);

        // 5 attendance sessions on CS101-A's meeting pattern. Present sets chosen so the post-hoc percentage
        // matches the arithmetic documented in this method's own javadoc exactly (5/5, 3/5, 0/5).
        for (int i = 1; i <= 5; i++) {
            userDirectoryService.authenticateForSeed(collegeAdmin.userId());
            String[] opened = attendanceSessionService.openForSeed(section1Id, meeting1Id, Instant.now().plusSeconds(1800)).split("\\|", 2);
            UUID sessionId = UUID.fromString(opened[0]);
            String rawToken = opened[1];
            for (SeededStudent present : presentForSession(i, strong, average, attendanceRisk, assessmentRisk, learningRisk)) {
                userDirectoryService.authenticateForSeed(present.account().userId());
                attendanceRecordService.scan(sessionId, rawToken);
            }
            userDirectoryService.authenticateForSeed(collegeAdmin.userId());
            attendanceSessionService.close(sessionId);
        }

        userDirectoryService.authenticateForSeed(collegeAdmin.userId());
        gradeService.publishOrCorrect(assessmentId, componentId, strong.studentId(), new BigDecimal("95"), "A", new BigDecimal("3.8"));
        gradeService.publishOrCorrect(assessmentId, componentId, average.studentId(), new BigDecimal("55"), "D", new BigDecimal("2.0"));
        gradeService.publishOrCorrect(assessmentId, componentId, attendanceRisk.studentId(), new BigDecimal("88"), "B+", new BigDecimal("3.5"));
        gradeService.publishOrCorrect(assessmentId, componentId, assessmentRisk.studentId(), new BigDecimal("15"), "F", BigDecimal.ZERO);
        gradeService.publishOrCorrect(assessmentId, componentId, learningRisk.studentId(), new BigDecimal("75"), "B", new BigDecimal("3.0"));
        gradeService.publishOrCorrect(assessmentId, componentId, intervention.studentId(), new BigDecimal("10"), "F", BigDecimal.ZERO);

        submitAs(strong, onTimeAssignmentId);
        submitAs(average, onTimeAssignmentId);
        submitAs(average, lateAssignmentId);
        submitAs(attendanceRisk, onTimeAssignmentId);
        submitAs(assessmentRisk, onTimeAssignmentId);
        submitAs(learningRisk, lateAssignmentId);
        submitAs(intervention, lateAssignmentId);

        recordProgress(strong, lesson1Id, LearningProgressState.COMPLETED, new BigDecimal("100"));
        recordProgress(strong, lesson2Id, LearningProgressState.COMPLETED, new BigDecimal("100"));
        recordProgress(average, lesson1Id, LearningProgressState.COMPLETED, new BigDecimal("100"));
        recordProgress(average, lesson2Id, LearningProgressState.IN_PROGRESS, new BigDecimal("40"));
        recordProgress(attendanceRisk, lesson1Id, LearningProgressState.COMPLETED, new BigDecimal("100"));
        recordProgress(attendanceRisk, lesson2Id, LearningProgressState.COMPLETED, new BigDecimal("100"));
        recordProgress(assessmentRisk, lesson1Id, LearningProgressState.COMPLETED, new BigDecimal("100"));
        recordProgress(assessmentRisk, lesson2Id, LearningProgressState.COMPLETED, new BigDecimal("100"));
        recordProgress(learningRisk, lesson1Id, LearningProgressState.IN_PROGRESS, new BigDecimal("30"));
        recordProgress(learningRisk, lesson2Id, LearningProgressState.IN_PROGRESS, new BigDecimal("10"));
        recordProgress(intervention, lesson1Id, LearningProgressState.IN_PROGRESS, new BigDecimal("20"));
        recordProgress(intervention, lesson2Id, LearningProgressState.IN_PROGRESS, new BigDecimal("5"));

        userDirectoryService.authenticateForSeed(collegeAdmin.userId());
        UUID interventionRiskScoreId = null;
        for (SeededStudent s : all) {
            Optional<UUID> riskScoreId = riskCalculationService.recalculateForSeed(s.studentId());
            if (riskScoreId.isEmpty()) {
                log.warn("Risk calculation could not find student {}", s.label());
                continue;
            }
            Optional<RiskScore> current = riskCalculationService.getCurrent(s.studentId());
            log.info("Risk calculated for {} ({}): band={}, riskScoreId={}",
                s.label(), s.account().email(), current.map(RiskScore::band).orElse(null), riskScoreId.get());
            if (s == intervention) {
                interventionRiskScoreId = riskScoreId.get();
            }
        }

        // The HIGH-band "intervention" student: accept the auto-proposed recommendation (RiskCalculationService
        // proposes one automatically whenever a recalculation newly crosses into MEDIUM/HIGH) to create a real,
        // linked intervention row, then move it into progress with one recorded action — never a hand-inserted
        // interventions row.
        List<Recommendation> recs = interventionRiskScoreId == null
            ? List.of() : recommendationService.listForRiskScore(interventionRiskScoreId);
        if (!recs.isEmpty()) {
            Optional<UUID> interventionId = recommendationService.acceptForSeed(recs.get(0).id(), LocalDate.now().plusDays(14));
            if (interventionId.isPresent()) {
                interventionService.transition(interventionId.get(), InterventionStatus.IN_PROGRESS);
                interventionService.addAction(interventionId.get(), "ADVISOR_MEETING_SCHEDULED",
                    "Initial advisor meeting scheduled to review attendance, grades, and engagement.");
            }
        }

        seedSectionTwoContent(collegeAdmin.userId(), faculty2UserId, section2Id, meeting2Id, strong, average, attendanceRisk);

        return all;
    }

    /**
     * Real gap found live: CS201-A (faculty2 / Vikram Mehta's own section) had zero modules, assignments,
     * assessments, or attendance sessions of its own — everything academic was seeded only under CS101-A
     * (faculty1). Logging in as faculty.mehta@demo.setu.local showed genuinely empty pages everywhere except
     * the section roster. This gives his section the same real depth, scoped to the three students actually
     * enrolled in it. Deliberately runs AFTER {@link RiskCalculationService#recalculateForSeed} already ran for
     * these students against CS101-A's data alone — the risk scores' documented arithmetic (this method's own
     * class-level javadoc) must stay exactly reproducible against that snapshot, not shift because of academic
     * activity added afterward in a different section.
     */
    private void seedSectionTwoContent(UUID collegeAdminUserId, UUID faculty2UserId, UUID section2Id, UUID meeting2Id,
                                        SeededStudent strong, SeededStudent average, SeededStudent attendanceRisk) {
        userDirectoryService.authenticateForSeed(faculty2UserId);
        UUID moduleId = courseModuleService.createForSeed(section2Id, "Module 1: Algorithmic Complexity", 1);
        // Real, public learning material (verified to exist when this seed was written). Example content: replace with the college's own.
        UUID bigO = lessonService.createForSeed(moduleId, "Big-O Notation", LessonType.VIDEO,
            "https://www.youtube.com/watch?v=g2o22C3CRfU", 1, 2);
        attachResource(bigO, com.setu.learning.domain.LessonResourceType.DOCUMENT, "https://wiki.python.org/moin/TimeComplexity",
            "Time complexity of Python's built-in operations (python.org wiki)");
        attachResource(bigO, com.setu.learning.domain.LessonResourceType.DOCUMENT, "https://opendatastructures.org/ods-python.pdf",
            "Open Data Structures, Python edition (free PDF, Pat Morin)");
        UUID structures = lessonService.createForSeed(moduleId, "Data Structures: Easy to Advanced", LessonType.VIDEO,
            "https://www.youtube.com/watch?v=RBSGKlAvoiM", 2, null);
        attachResource(structures, com.setu.learning.domain.LessonResourceType.DOCUMENT, "https://docs.python.org/3/tutorial/datastructures.html",
            "The Python Tutorial: data structures (python.org)");
        UUID inPython = lessonService.createForSeed(moduleId, "Data Structures and Algorithms in Python", LessonType.VIDEO,
            "https://www.youtube.com/watch?v=pkYVOmU3MgA", 3, null);
        attachResource(inPython, com.setu.learning.domain.LessonResourceType.DOCUMENT, "https://opendatastructures.org/ods-python.pdf",
            "Open Data Structures, Python edition (free PDF, Pat Morin)");

        UUID assignmentId = assignmentService.createForSeed(section2Id, "A1 - Sorting Algorithms Report",
            new BigDecimal("100"), Instant.now().plusSeconds(86400L * 20), null);
        assignmentService.publish(assignmentId);
        submitAs(strong, assignmentId);
        submitAs(average, assignmentId);
        submitAs(attendanceRisk, assignmentId);

        // MANAGE_ASSESSMENTS (unlike MANAGE_ASSIGNMENTS) is not granted to FACULTY as a blanket role permission —
        // only MANAGE_ASSESSMENTS_ALL_SECTIONS (college admin) reaches it, matching CS101-A's identical precedent
        // above. GradeService.publishOrCorrect delegates to the same assertManage, so grades need it too.
        userDirectoryService.authenticateForSeed(collegeAdminUserId);
        UUID assessmentId = assessmentService.createForSeed(section2Id, "QUIZ", "Data Structures Midterm",
            new BigDecimal("100"), 60, Instant.now().minusSeconds(3600), Instant.now().plusSeconds(3600));
        UUID componentId = assessmentComponentService.createForSeed(assessmentId, "MAIN", new BigDecimal("100"),
            new BigDecimal("100"), 1);
        seedQuizQuestions(componentId, "DATA_STRUCTURES");
        assessmentService.schedule(assessmentId);
        assessmentService.open(assessmentId);
        gradeService.publishOrCorrect(assessmentId, componentId, strong.studentId(), new BigDecimal("91"), "A", new BigDecimal("3.7"));
        gradeService.publishOrCorrect(assessmentId, componentId, average.studentId(), new BigDecimal("68"), "C+", new BigDecimal("2.5"));
        gradeService.publishOrCorrect(assessmentId, componentId, attendanceRisk.studentId(), new BigDecimal("79"), "B", new BigDecimal("3.1"));

        userDirectoryService.authenticateForSeed(faculty2UserId);
        String[] opened = attendanceSessionService.openForSeed(section2Id, meeting2Id, Instant.now().plusSeconds(1800)).split("\\|", 2);
        UUID sessionId = UUID.fromString(opened[0]);
        String rawToken = opened[1];
        for (SeededStudent present : List.of(strong, average)) { // attendanceRisk stays true to its scenario: absent here too
            userDirectoryService.authenticateForSeed(present.account().userId());
            attendanceRecordService.scan(sessionId, rawToken);
        }
        userDirectoryService.authenticateForSeed(faculty2UserId);
        attendanceSessionService.close(sessionId);
    }

    private List<SeededStudent> presentForSession(int sessionNo, SeededStudent strong, SeededStudent average,
                                                    SeededStudent attendanceRisk, SeededStudent assessmentRisk,
                                                    SeededStudent learningRisk) {
        List<SeededStudent> present = new ArrayList<>();
        present.add(strong); // 5/5 = 100%
        if (sessionNo <= 3) {
            present.add(average); // 3/5 = 60%
        }
        // attendanceRisk and intervention never scan: 0/5 = 0%
        present.add(assessmentRisk); // 5/5 = 100%, clean attendance so assessment stays the sole dominant factor
        present.add(learningRisk); // 5/5 = 100%, clean attendance so LMS/lateness stays the sole dominant factor
        return present;
    }

    private void submitAs(SeededStudent student, UUID assignmentId) {
        userDirectoryService.authenticateForSeed(student.account().userId());
        assignmentSubmissionService.submit(assignmentId, "ref://submissions/" + student.studentId());
    }

    private void recordProgress(SeededStudent student, UUID lessonId, LearningProgressState state, BigDecimal percent) {
        userDirectoryService.authenticateForSeed(student.account().userId());
        learningProgressService.record(lessonId, state, percent, null);
    }

    /**
     * Two guardians, four grants spanning active-full, active-limited, revoked, and expired — plus one
     * relationship link with NO access grant at all, to demonstrate explicitly that a guardian relationship
     * alone never confers access (canonical spec: guardian access must be explicit, scoped, and revocable).
     */
    private void seedTenantAGuardians(UUID tenantId, Account collegeAdmin, List<SeededStudent> students, List<String> credentials) {
        SeededStudent strong = students.get(0);
        SeededStudent average = students.get(1);
        SeededStudent attendanceRisk = students.get(2);
        SeededStudent assessmentRisk = students.get(3);
        SeededStudent intervention = students.get(5);

        userDirectoryService.authenticateForSeed(collegeAdmin.userId());
        String guardian1Email = demoEmail("guardian.kapoor");
        UUID guardian1UserId = registerAndPassword(guardian1Email, credentials, "GUARDIAN (Rajesh Kapoor)");
        UUID guardian1PersonId = personDirectory.createPerson(guardian1UserId, "Rajesh Kapoor", "Rajesh",
            LocalDate.of(1975, 3, 10), "UNSPECIFIED", guardian1Email, "+91-9800000101", null);
        userDirectoryService.grantBootstrapRole(guardian1UserId, RoleCode.GUARDIAN, tenantId);
        UUID guardian1Id = guardianProfileService.createForSeed(tenantId, guardian1PersonId, "Engineer", "Brightline Technologies", null);

        String guardian2Email = demoEmail("guardian.nair");
        UUID guardian2UserId = registerAndPassword(guardian2Email, credentials, "GUARDIAN (Lakshmi Nair)");
        UUID guardian2PersonId = personDirectory.createPerson(guardian2UserId, "Lakshmi Nair", "Lakshmi",
            LocalDate.of(1978, 11, 2), "UNSPECIFIED", guardian2Email, "+91-9800000102", null);
        userDirectoryService.grantBootstrapRole(guardian2UserId, RoleCode.GUARDIAN, tenantId);
        UUID guardian2Id = guardianProfileService.createForSeed(tenantId, guardian2PersonId, "Teacher", "Sunrise Public School", null);

        // Relationships (directory.student_guardians) — deliberately separate from access grants below.
        studentGuardianService.link(strong.studentId(), guardian1Id, "FATHER", true, true);
        studentGuardianService.link(assessmentRisk.studentId(), guardian1Id, "GUARDIAN", false, false);
        studentGuardianService.link(intervention.studentId(), guardian1Id, "GUARDIAN", false, false); // relationship only, NO grant below
        studentGuardianService.link(average.studentId(), guardian2Id, "MOTHER", true, true);
        studentGuardianService.link(attendanceRisk.studentId(), guardian2Id, "GUARDIAN", false, false);

        // Grant A: guardian1 -> strong student, ACTIVE, full supported permission set.
        guardianAccessService.grantAccessForSeed(strong.studentId(), guardian1Id, null,
            Set.of("VIEW_GRADES", "VIEW_ATTENDANCE", "VIEW_SCHEDULE", "VIEW_ACADEMIC_STATUS",
                "VIEW_LEARNING_DATA", "VIEW_FINANCE", "VIEW_RISK_SCORE"));

        // Grant B: guardian2 -> average student, ACTIVE, but VIEW_RISK_SCORE only — proves a guardian scoped to
        // risk visibility alone cannot see recommendations/interventions (authorization-matrix.md: guardians get
        // "no access" for that row regardless of any permission code they hold).
        guardianAccessService.grantAccessForSeed(average.studentId(), guardian2Id, null, Set.of("VIEW_RISK_SCORE"));

        // Grant C: guardian2 -> attendanceRisk student, created then immediately REVOKED.
        UUID grantCId = guardianAccessService.grantAccessForSeed(attendanceRisk.studentId(), guardian2Id, null,
            Set.of("VIEW_ATTENDANCE"));
        guardianAccessService.revokeAccess(grantCId, "Demo data: revoked grant example");

        // Grant D: guardian1 -> assessmentRisk student, EXPIRED. guardian_access_grants' own CHECK constraint
        // (V16: "expires_at IS NULL OR expires_at > granted_at") means a grant can never be created already
        // expired — so this is created with an expiry a few seconds out and is naturally expired by the time
        // anything (a reviewer, Phase 2's own verification) reads it back, exactly matching GuardianAccessGrant
        // isUsable(now)'s real semantics rather than a backdated row that could never have existed for real.
        guardianAccessService.grantAccessForSeed(assessmentRisk.studentId(), guardian1Id, Instant.now().plusSeconds(2),
            Set.of("VIEW_GRADES"));
    }

    private void seedTenantACareer(UUID tenantId, Account placementOfficer, List<SeededStudent> students, List<String> credentials) {
        userDirectoryService.authenticateForSeed(placementOfficer.userId());
        UUID categoryId = skillCategoryService.createForSeed(tenantId, "SOFTWARE", "Software Engineering");
        UUID javaSkillId = skillService.createForSeed(tenantId, "JAVA", "Java Programming", categoryId);
        skillService.createForSeed(tenantId, "SQL", "SQL & Relational Databases", categoryId);

        UUID jobId = jobService.createForSeed(tenantId, "Graduate Software Engineer", "Brightline Technologies",
            "Entry-level software engineering role building backend services.");
        jobSkillService.add(jobId, javaSkillId, ProficiencyLevel.INTERMEDIATE);

        SeededStudent strong = students.get(0);
        userDirectoryService.authenticateForSeed(strong.account().userId());
        studentSkillService.add(strong.studentId(), javaSkillId, ProficiencyLevel.ADVANCED, "Coursework + personal projects");
        Optional<UUID> applicationId = applicationService.applyForSeed(jobId, strong.studentId());
        if (applicationId.isPresent()) {
            userDirectoryService.authenticateForSeed(placementOfficer.userId());
            applicationService.shortlist(applicationId.get());
            applicationService.select(applicationId.get());
            placementRecordService.record(strong.studentId(), jobId, "Brightline Technologies", "Graduate Software Engineer",
                new BigDecimal("650000"), LocalDate.now().plusMonths(2));
        }

        // A second job in a different, still-OPEN lifecycle state (no application yet) — so the placement
        // officer's own dashboard/jobs page has more than one row to demonstrate, and a reviewer can see what
        // an unfilled listing looks like next to a filled one.
        UUID dbSkillId = skillService.createForSeed(tenantId, "SQL2", "Database Design", categoryId);
        UUID job2Id = jobService.createForSeed(tenantId, "Data Analyst Intern", "Kestrel Analytics",
            "Six-month internship analyzing academic performance datasets and building reporting dashboards.");
        jobSkillService.add(job2Id, dbSkillId, ProficiencyLevel.BEGINNER);

        SeededStudent average = students.get(1);
        userDirectoryService.authenticateForSeed(average.account().userId());
        studentSkillService.add(average.studentId(), javaSkillId, ProficiencyLevel.BEGINNER, "Introductory coursework");
        Optional<UUID> secondApplicationId = applicationService.applyForSeed(job2Id, average.studentId());
        secondApplicationId.ifPresent(id -> {
            userDirectoryService.authenticateForSeed(placementOfficer.userId());
            applicationService.shortlist(id); // left SHORTLISTED, not yet decided — a real in-progress state
        });
    }

    private void seedTenantAFinance(UUID tenantId, Account collegeAdmin, UUID programId, UUID yearId,
                                     List<SeededStudent> students, List<String> credentials) {
        userDirectoryService.authenticateForSeed(collegeAdmin.userId());
        UUID feeStructureId = feeStructureService.createForSeed(tenantId, programId, "SIT Tuition & Fees 2024-25", yearId);
        UUID tuitionComponentId = feeComponentService.createForSeed(feeStructureId, "TUITION", new BigDecimal("50000"), "INR");
        UUID libraryComponentId = feeComponentService.createForSeed(feeStructureId, "LIBRARY", new BigDecimal("2000"), "INR");
        UUID labComponentId = feeComponentService.createForSeed(feeStructureId, "LAB", new BigDecimal("3000"), "INR");

        SeededStudent strong = students.get(0);
        SeededStudent average = students.get(1);
        SeededStudent attendanceRisk = students.get(2);
        SeededStudent assessmentRisk = students.get(3);
        SeededStudent learningRisk = students.get(4);

        // Pending charge — never paid, demonstrates an outstanding balance.
        studentChargeService.create(strong.studentId(), tuitionComponentId, LocalDate.now().plusDays(30));

        // Overdue charge.
        UUID overdueChargeId = studentChargeService.createForSeed(average.studentId(), libraryComponentId, LocalDate.now().minusDays(10));
        studentChargeService.markOverdue(overdueChargeId);

        // Successful payment + receipt.
        studentChargeService.create(attendanceRisk.studentId(), labComponentId, LocalDate.now().plusDays(15));
        userDirectoryService.authenticateForSeed(attendanceRisk.account().userId());
        Optional<UUID> successPaymentId = paymentService.initiatePaymentForSeed(attendanceRisk.studentId(),
            new BigDecimal("3000"), "INR", "UPI", "seed-key-" + UUID.randomUUID());
        userDirectoryService.authenticateForSeed(collegeAdmin.userId());
        successPaymentId.ifPresent(paymentId -> paymentService.recordWebhookEvent(paymentId,
            "evt-seed-success-" + UUID.randomUUID(), "PAYMENT_SUCCEEDED", "{\"provider\":\"demo\",\"outcome\":\"success\"}"));

        // Failed payment.
        studentChargeService.create(assessmentRisk.studentId(), labComponentId, LocalDate.now().plusDays(15));
        userDirectoryService.authenticateForSeed(assessmentRisk.account().userId());
        Optional<UUID> failedPaymentId = paymentService.initiatePaymentForSeed(assessmentRisk.studentId(),
            new BigDecimal("3000"), "INR", "UPI", "seed-key-" + UUID.randomUUID());
        userDirectoryService.authenticateForSeed(collegeAdmin.userId());
        failedPaymentId.ifPresent(paymentId -> paymentService.recordWebhookEvent(paymentId,
            "evt-seed-failed-" + UUID.randomUUID(), "PAYMENT_FAILED", "{\"provider\":\"demo\",\"outcome\":\"failure\"}"));

        // Successful payment, later refunded.
        studentChargeService.create(learningRisk.studentId(), labComponentId, LocalDate.now().plusDays(15));
        userDirectoryService.authenticateForSeed(learningRisk.account().userId());
        Optional<UUID> refundPaymentId = paymentService.initiatePaymentForSeed(learningRisk.studentId(),
            new BigDecimal("3000"), "INR", "UPI", "seed-key-" + UUID.randomUUID());
        userDirectoryService.authenticateForSeed(collegeAdmin.userId());
        refundPaymentId.ifPresent(paymentId -> {
            paymentService.recordWebhookEvent(paymentId, "evt-seed-refund-src-" + UUID.randomUUID(),
                "PAYMENT_SUCCEEDED", "{\"provider\":\"demo\",\"outcome\":\"success\"}");
            paymentService.refund(paymentId);
        });
    }

    // ---------------------------------------------------------------- Tenant B: cross-tenant boundary demo

    /**
     * A second, fully independent tenant — its only purpose is to give a reviewer a real, separately-owned
     * resource to attempt cross-tenant access against (Tenant A's college admin, faculty, and students must
     * all be denied here, and vice versa). Deliberately smaller than Tenant A: cross-tenant denial itself is
     * already covered by dozens of automated tests (see {@code docs/demo-data-plan.md}); the seed data's job
     * is just to give a human reviewer something real to click on.
     */
    private void seedTenantB(List<String> credentials) {
        UUID tenantId = collegeDirectoryService.provisionCollege(demoCode("MRD"), "Meridian College of Arts & Sciences", "Asia/Kolkata");
        log.info("Tenant B provisioned: Meridian College of Arts & Sciences ({})", tenantId);

        String collegeAdminEmail = demoEmail("admin.mrd");
        Account collegeAdmin = new Account(
            registerAndPassword(collegeAdminEmail, credentials, "COLLEGE_ADMIN (Meridian College of Arts & Sciences)"),
            collegeAdminEmail);
        userDirectoryService.grantBootstrapRole(collegeAdmin.userId(), RoleCode.COLLEGE_ADMIN, tenantId);
        personDirectory.createPerson(collegeAdmin.userId(), "Anjali Bhatt", "Anjali", LocalDate.of(1979, 5, 30),
            "UNSPECIFIED", collegeAdminEmail, "+91-9800000204", null);
        userDirectoryService.authenticateForSeed(collegeAdmin.userId());

        UUID campusId = campusService.createForSeed(tenantId, "MAIN", "Meridian Main Campus");
        UUID orgId = academicOrganizationService.createForSeed(campusId, null, "School of Humanities", "SCHOOL");
        UUID deptId = departmentService.createForSeed(orgId, "ENG", "English Literature");
        UUID programId = programService.createForSeed(deptId, "BA-ENG", "B.A. English Literature", "BACHELOR", 6,
            new BigDecimal("120"));
        UUID curriculumId = curriculumService.createReferenceForSeed(programId, "B.A. English 2024 Curriculum", "v2024",
            LocalDate.of(2024, 6, 1), null);
        curriculumRequirementService.createForSeed(curriculumId, "CORE", new BigDecimal("72"), 18, null);
        curriculumRequirementService.createForSeed(curriculumId, "ELECTIVE", new BigDecimal("30"), 8, null);
        curriculumRequirementService.createForSeed(curriculumId, "GENERAL_EDUCATION", new BigDecimal("18"), 5, null);
        UUID yearId = academicYearService.createYear(tenantId, "AY2024-25", LocalDate.of(2024, 6, 1), LocalDate.of(2025, 5, 31));
        academicYearService.markCurrent(yearId);
        Instant regOpen = Instant.now().minusSeconds(86400L * 60).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        Instant regClose = Instant.now().minusSeconds(86400L * 30).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        UUID periodId = academicPeriodService.createPeriodForSeed(yearId, "SEM1", "Semester 1", 1,
            LocalDate.of(2024, 6, 1), LocalDate.of(2024, 11, 30), regOpen, regClose);
        academicPeriodService.transitionTo(periodId, AcademicPeriod.PeriodStatus.REGISTRATION_OPEN);

        UUID courseId = courseService.createForSeed(deptId, "ENG101", "Introduction to English Literature",
            new BigDecimal("3"), "CORE", "UG", "Survey of major literary movements.");
        courseService.changeStatus(courseId, CourseStatus.ACTIVE);
        UUID buildingId = buildingService.createForSeed(tenantId, "HUM-1", "Humanities Building");
        UUID roomId = roomService.createForSeed(buildingId, "H201", 40, "{}");
        UUID sectionId = courseSectionService.createForSeed(courseId, periodId, null, "ENG101-A", 10, roomId, regOpen, regClose);
        courseSectionService.changeStatus(sectionId, CourseSectionStatus.OPEN);
        meetingPatternService.createForSeed(sectionId, (short) 2, LocalTime.of(11, 0), LocalTime.of(12, 0), roomId,
            LocalDate.of(2024, 6, 1), null);

        SeededFaculty faculty = seedFaculty(tenantId, demoEmail("faculty.mrd"), "Devika Menon", "EMP-MRD-001",
            "Assistant Professor", collegeAdmin.userId(), credentials);
        userDirectoryService.authenticateForSeed(collegeAdmin.userId());
        facultyAssignmentService.assign(sectionId, faculty.facultyId(), FacultyAssignmentRole.PRIMARY,
            new BigDecimal("100"), LocalDate.of(2024, 6, 1), null);

        SeededStudent student = admitStudent(tenantId, collegeAdmin.userId(), demoEmail("student.mrd"),
            "Kavya Pillai", "ADM-MRD-1001", "TENANT-B", credentials);
        userDirectoryService.authenticateForSeed(collegeAdmin.userId());
        studentProgramService.assign(student.studentId(), programId, curriculumId, 2024, true, LocalDate.of(2024, 6, 1));
        userDirectoryService.authenticateForSeed(student.account().userId());
        enrollmentService.request(student.studentId(), sectionId);

        userDirectoryService.authenticateForSeed(collegeAdmin.userId());
        UUID assessmentId = assessmentService.createForSeed(sectionId, "QUIZ", "Reading Response Quiz",
            new BigDecimal("100"), 45, Instant.now().minusSeconds(3600), Instant.now().plusSeconds(3600));
        UUID componentId = assessmentComponentService.createForSeed(assessmentId, "MAIN", new BigDecimal("100"),
            new BigDecimal("100"), 1);
        seedQuizQuestions(componentId, "READING");
        assessmentService.schedule(assessmentId);
        assessmentService.open(assessmentId);
        gradeService.publishOrCorrect(assessmentId, componentId, student.studentId(), new BigDecimal("82"), "B+", new BigDecimal("3.4"));

        riskCalculationService.recalculateForSeed(student.studentId());

        policyDocumentService.create(tenantId, "Attendance Policy",
            "Meridian College requires at least 75% attendance per enrolled section for assessment eligibility.", null);
    }
}
