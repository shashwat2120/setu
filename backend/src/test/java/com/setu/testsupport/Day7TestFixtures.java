package com.setu.testsupport;

import com.setu.catalog.application.AcademicPeriodService;
import com.setu.catalog.application.AcademicYearService;
import com.setu.catalog.application.BuildingService;
import com.setu.catalog.application.CourseService;
import com.setu.catalog.application.CurriculumService;
import com.setu.catalog.application.ProgramService;
import com.setu.catalog.application.RoomService;
import com.setu.directory.application.FacultyProfileService;
import com.setu.enrollment.application.CourseSectionService;
import com.setu.identity.TestAuthentication;
import com.setu.identity.application.UserRegistrationService;
import com.setu.identity.domain.RoleCode;
import com.setu.identity.domain.UserRole;
import com.setu.identity.infrastructure.RoleRepository;
import com.setu.identity.infrastructure.UserRoleRepository;
import com.setu.institution.application.AcademicOrganizationService;
import com.setu.institution.application.CampusService;
import com.setu.institution.application.CollegeDirectoryService;
import com.setu.institution.application.DepartmentService;
import com.setu.shared.identity.PersonDirectory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Test-only fixture builder shared across the Day 7 test suite
 * (curriculum/course/section/faculty-assignment/room/building/meeting-
 * pattern tests) so each test class doesn't re-derive the same
 * tenant-&gt;department-&gt;program-&gt;course-&gt;section provisioning chain. Every
 * method goes through the real application services (never a repository
 * directly), authenticating as the tenant admin it creates for the
 * duration of the call, matching this project's established
 * {@code @SpringBootTest} + real-PostgreSQL integration-test convention
 * (see {@code catalog.application.ProgramTests} for the pattern this
 * generalizes).
 */
@Component
public class Day7TestFixtures {

    private final CollegeDirectoryService collegeDirectoryService;
    private final UserRegistrationService userRegistrationService;
    private final RoleRepository roleRepository;
    private final UserRoleRepository userRoleRepository;
    private final CampusService campusService;
    private final AcademicOrganizationService organizationService;
    private final DepartmentService departmentService;
    private final ProgramService programService;
    private final CurriculumService curriculumService;
    private final CourseService courseService;
    private final AcademicYearService academicYearService;
    private final AcademicPeriodService academicPeriodService;
    private final BuildingService buildingService;
    private final RoomService roomService;
    private final PersonDirectory personDirectory;
    private final FacultyProfileService facultyProfileService;
    private final CourseSectionService courseSectionService;

    public Day7TestFixtures(CollegeDirectoryService collegeDirectoryService, UserRegistrationService userRegistrationService,
                             RoleRepository roleRepository, UserRoleRepository userRoleRepository, CampusService campusService,
                             AcademicOrganizationService organizationService, DepartmentService departmentService,
                             ProgramService programService, CurriculumService curriculumService, CourseService courseService,
                             AcademicYearService academicYearService, AcademicPeriodService academicPeriodService,
                             BuildingService buildingService, RoomService roomService, PersonDirectory personDirectory,
                             FacultyProfileService facultyProfileService, CourseSectionService courseSectionService) {
        this.collegeDirectoryService = collegeDirectoryService;
        this.userRegistrationService = userRegistrationService;
        this.roleRepository = roleRepository;
        this.userRoleRepository = userRoleRepository;
        this.campusService = campusService;
        this.organizationService = organizationService;
        this.departmentService = departmentService;
        this.programService = programService;
        this.curriculumService = curriculumService;
        this.courseService = courseService;
        this.academicYearService = academicYearService;
        this.academicPeriodService = academicPeriodService;
        this.buildingService = buildingService;
        this.roomService = roomService;
        this.personDirectory = personDirectory;
        this.facultyProfileService = facultyProfileService;
        this.courseSectionService = courseSectionService;
    }

    public record Tenant(UUID tenantId, UUID adminUserId) {
    }

    public Tenant provisionTenant(String prefix) {
        UUID tenant = collegeDirectoryService.provisionCollege(prefix + UUID.randomUUID().toString().substring(0, 8),
            prefix + " College", "Asia/Kolkata");
        UUID admin = provisionUserWithRole(tenant, RoleCode.COLLEGE_ADMIN, prefix + "-admin");
        return new Tenant(tenant, admin);
    }

    /**
     * When a freshly created test role takes effect. Authorization treats a role as active only if its start is not after a
     * fresh {@code Instant.now()}, so a start of exactly "now" can look not-yet-active if the machine's clock steps back or
     * jitters by a few milliseconds (seen once as a spurious {@code tenant_mismatch} in an otherwise passing full run). Starting a few
     * seconds in the past removes that dependency on the clock without changing what any test asserts.
     */
    public static java.time.Instant roleStartedAt() {
        return java.time.Instant.now().minusSeconds(5);
    }

    public UUID provisionUserWithRole(UUID tenantId, RoleCode role, String emailPrefix) {
        var outcome = (UserRegistrationService.Outcome.Created)
            userRegistrationService.register(emailPrefix + "-" + UUID.randomUUID() + "@example.com");
        UUID userId = outcome.userId();
        UUID roleId = roleRepository.findByCode(role).orElseThrow().id();
        userRoleRepository.save(new UserRole(UUID.randomUUID(), userId, roleId, tenantId, Day7TestFixtures.roleStartedAt(), null));
        return userId;
    }

    public UUID provisionDepartment(Tenant tenant, String code) {
        TestAuthentication.authenticateAs(tenant.adminUserId());
        try {
            var campus = (CampusService.CreateResult.Success) campusService.create(tenant.tenantId(), code + "-C", "Main Campus");
            var org = (AcademicOrganizationService.CreateResult.Success)
                organizationService.create(campus.campusId(), null, code + " Faculty", "FACULTY");
            var dept = (DepartmentService.CreateResult.Success) departmentService.create(org.organizationId(), code, code + " Department");
            return dept.departmentId();
        } finally {
            TestAuthentication.clear();
        }
    }

    public UUID provisionProgram(Tenant tenant, UUID departmentId, String code) {
        TestAuthentication.authenticateAs(tenant.adminUserId());
        try {
            var result = (ProgramService.CreateResult.Success)
                programService.create(departmentId, code, code + " Program", "BACHELORS", 8, new BigDecimal("160.00"));
            return result.programId();
        } finally {
            TestAuthentication.clear();
        }
    }

    public UUID provisionCurriculum(Tenant tenant, UUID programId, String versionLabel, LocalDate from, LocalDate to) {
        TestAuthentication.authenticateAs(tenant.adminUserId());
        try {
            var result = (CurriculumService.CreateResult.Success)
                curriculumService.createReference(programId, "Curriculum " + versionLabel, versionLabel, from, to);
            return result.curriculumId();
        } finally {
            TestAuthentication.clear();
        }
    }

    public UUID provisionCourse(Tenant tenant, UUID departmentId, String code) {
        TestAuthentication.authenticateAs(tenant.adminUserId());
        try {
            var result = (CourseService.CreateResult.Success) courseService.create(departmentId, code, code + " Title",
                new BigDecimal("4.0"), "LECTURE", "UNDERGRADUATE", "A course");
            return result.courseId();
        } finally {
            TestAuthentication.clear();
        }
    }

    public UUID provisionBuilding(Tenant tenant, String code) {
        TestAuthentication.authenticateAs(tenant.adminUserId());
        try {
            var result = (BuildingService.CreateResult.Success) buildingService.create(tenant.tenantId(), code, code + " Building");
            return result.buildingId();
        } finally {
            TestAuthentication.clear();
        }
    }

    public UUID provisionRoom(Tenant tenant, UUID buildingId, String code, int capacity) {
        TestAuthentication.authenticateAs(tenant.adminUserId());
        try {
            var result = (RoomService.CreateResult.Success) roomService.create(buildingId, code, capacity, null);
            return result.roomId();
        } finally {
            TestAuthentication.clear();
        }
    }

    public UUID provisionPeriod(Tenant tenant, String code) {
        TestAuthentication.authenticateAs(tenant.adminUserId());
        try {
            UUID yearId = academicYearService.createYear(tenant.tenantId(), code + "-YR", LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31));
            var result = academicPeriodService.createPeriod(yearId, code, code + " Period", 1,
                LocalDate.of(2025, 1, 1), LocalDate.of(2025, 6, 30), null, null);
            return ((AcademicPeriodService.CreateResult.Success) result).periodId();
        } finally {
            TestAuthentication.clear();
        }
    }

    public UUID provisionFaculty(Tenant tenant, String emailPrefix) {
        UUID facultyUserId = provisionUserWithRole(tenant.tenantId(), RoleCode.FACULTY, emailPrefix);
        TestAuthentication.authenticateAs(tenant.adminUserId());
        try {
            UUID personId = personDirectory.createPerson(facultyUserId, emailPrefix + " Legal Name", null, null, null,
                emailPrefix + "@example.com", null, null);
            var result = (FacultyProfileService.CreateResult.Success)
                facultyProfileService.create(tenant.tenantId(), personId, emailPrefix + "-EMP", "Lecturer", LocalDate.of(2020, 1, 1));
            return result.facultyId();
        } finally {
            TestAuthentication.clear();
        }
    }

    public UUID provisionSection(Tenant tenant, UUID courseId, UUID periodId, String sectionCode, int capacity, UUID roomId) {
        TestAuthentication.authenticateAs(tenant.adminUserId());
        try {
            var result = (CourseSectionService.CreateResult.Success)
                courseSectionService.create(courseId, periodId, null, sectionCode, capacity, roomId, null, null);
            return result.sectionId();
        } finally {
            TestAuthentication.clear();
        }
    }
}
