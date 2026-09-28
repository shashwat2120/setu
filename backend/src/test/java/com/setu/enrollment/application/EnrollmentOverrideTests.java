package com.setu.enrollment.application;

import com.setu.catalog.application.CoursePrerequisiteService;
import com.setu.catalog.domain.PrerequisiteRuleType;
import com.setu.enrollment.domain.OverrideType;
import com.setu.identity.TestAuthentication;
import com.setu.testsupport.Day7TestFixtures;
import com.setu.testsupport.Day8TestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Day 8 §4 enrollment-override behavior. */
// Not @Transactional (Sprint 5D hardening): several Day 7 fixture methods now insert through a REQUIRES_NEW guard
// (see e.g. BuildingInsertGuard), so an uncommitted @Transactional test transaction's own fixture rows would be
// invisible to that guard's separate connection — the same lesson already applied across this codebase's other
// REQUIRES_NEW-touching test classes.
@SpringBootTest
class EnrollmentOverrideTests {

    @Autowired
    Day7TestFixtures fixtures;
    @Autowired
    Day8TestFixtures day8Fixtures;
    @Autowired
    EnrollmentService enrollmentService;
    @Autowired
    EnrollmentOverrideService overrideService;
    @Autowired
    CoursePrerequisiteService coursePrerequisiteService;
    @Autowired
    com.setu.identity.infrastructure.RoleRepository roleRepository;
    @Autowired
    com.setu.identity.infrastructure.UserRoleRepository userRoleRepository;

    @AfterEach
    void clearSecurityContext() {
        TestAuthentication.clear();
    }

    private record Scenario(Day7TestFixtures.Tenant tenant, UUID courseId, UUID sectionId, Day8TestFixtures.Student student) {
    }

    private Scenario setUp(String prefix) {
        var tenant = fixtures.provisionTenant(prefix);
        UUID dept = fixtures.provisionDepartment(tenant, prefix + "D");
        UUID programId = fixtures.provisionProgram(tenant, dept, prefix + "P");
        UUID curriculumId = fixtures.provisionCurriculum(tenant, programId, "v1", LocalDate.of(2025, 1, 1), null);
        UUID courseId = fixtures.provisionCourse(tenant, dept, prefix + "C");
        UUID periodId = day8Fixtures.provisionOpenPeriod(tenant, prefix + "PR");
        UUID sectionId = day8Fixtures.provisionOpenSection(tenant, courseId, periodId, "S1", 10, null);
        var student = day8Fixtures.provisionStudentWithActiveProgram(tenant, programId, curriculumId, prefix + "S1");
        return new Scenario(tenant, courseId, sectionId, student);
    }

    @Test
    void adminCanCreateOverride() {
        var s = setUp("OVR1");
        TestAuthentication.authenticateAs(s.tenant.adminUserId());

        var result = overrideService.create(s.student.studentId(), s.sectionId, OverrideType.CAPACITY, "manual exception", null);

        assertThat(result).isInstanceOf(EnrollmentOverrideService.CreateResult.Success.class);
    }

    @Test
    void studentCannotSelfApproveOverride() {
        var s = setUp("OVR2");
        // The student somehow also holds MANAGE_ENROLLMENT_OVERRIDES in-tenant (contrived, but proves the self-approval guard fires even if role-based permission alone would have allowed it).
        var role = roleRepository.findByCode(com.setu.identity.domain.RoleCode.COLLEGE_ADMIN).orElseThrow();
        userRoleRepository.save(new com.setu.identity.domain.UserRole(UUID.randomUUID(), s.student.userId(), role.id(), s.tenant.tenantId(), Instant.now(), null));
        TestAuthentication.authenticateAs(s.student.userId());

        var result = overrideService.create(s.student.studentId(), s.sectionId, OverrideType.CAPACITY, "self exception", null);

        assertThat(result).isInstanceOf(EnrollmentOverrideService.CreateResult.SelfApprovalRejected.class);
    }

    @Test
    void facultyCannotCreateOverride() {
        var s = setUp("OVR3");
        UUID facultyUserId = fixtures.provisionUserWithRole(s.tenant.tenantId(), com.setu.identity.domain.RoleCode.FACULTY, "OVR3-FAC");
        TestAuthentication.authenticateAs(facultyUserId);

        assertThatThrownBy(() -> overrideService.create(s.student.studentId(), s.sectionId, OverrideType.CAPACITY, "faculty attempt", null))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void expiredOverrideDoesNotAllowBypass() {
        var s = setUp("OVR4");
        UUID prereqDept = fixtures.provisionDepartment(s.tenant, "OVR4D2");
        UUID prereqCourse = fixtures.provisionCourse(s.tenant, prereqDept, "OVR4-PRE");
        TestAuthentication.authenticateAs(s.tenant.adminUserId());
        coursePrerequisiteService.addPrerequisite(s.courseId, prereqCourse, PrerequisiteRuleType.REQUIRED, null);
        overrideService.create(s.student.studentId(), s.sectionId, OverrideType.PREREQUISITE, "already expired grant",
            Instant.now().minus(1, ChronoUnit.DAYS));
        TestAuthentication.authenticateAs(s.student.userId());

        var result = enrollmentService.request(s.student.studentId(), s.sectionId);

        assertThat(result).isInstanceOf(EnrollmentService.RequestResult.Rejected.class);
        assertThat(((EnrollmentService.RequestResult.Rejected) result).reason())
            .isEqualTo(EnrollmentService.RejectReason.PREREQUISITE_NOT_SATISFIED);
    }

    @Test
    void activePrerequisiteOverrideAllowsEnrollmentDespiteUnsatisfiedPrerequisite() {
        var s = setUp("OVR5");
        UUID prereqDept = fixtures.provisionDepartment(s.tenant, "OVR5D2");
        UUID prereqCourse = fixtures.provisionCourse(s.tenant, prereqDept, "OVR5-PRE");
        TestAuthentication.authenticateAs(s.tenant.adminUserId());
        coursePrerequisiteService.addPrerequisite(s.courseId, prereqCourse, PrerequisiteRuleType.REQUIRED, null);
        overrideService.create(s.student.studentId(), s.sectionId, OverrideType.PREREQUISITE, "waived", null);
        TestAuthentication.authenticateAs(s.student.userId());

        var result = enrollmentService.request(s.student.studentId(), s.sectionId);

        assertThat(result).isInstanceOf(EnrollmentService.RequestResult.Enrolled.class);
    }

    @Test
    void usedOverrideCannotBeReusedForASecondAttempt() {
        var s = setUp("OVR6");
        UUID prereqDept = fixtures.provisionDepartment(s.tenant, "OVR6D2");
        UUID prereqCourse = fixtures.provisionCourse(s.tenant, prereqDept, "OVR6-PRE");
        TestAuthentication.authenticateAs(s.tenant.adminUserId());
        coursePrerequisiteService.addPrerequisite(s.courseId, prereqCourse, PrerequisiteRuleType.REQUIRED, null);
        overrideService.create(s.student.studentId(), s.sectionId, OverrideType.PREREQUISITE, "one time", null);
        TestAuthentication.authenticateAs(s.student.userId());
        UUID enrollmentId = ((EnrollmentService.RequestResult.Enrolled)
            enrollmentService.request(s.student.studentId(), s.sectionId)).enrollmentId();
        enrollmentService.drop(enrollmentId, "retry test");

        var result = enrollmentService.request(s.student.studentId(), s.sectionId);

        assertThat(result).isInstanceOf(EnrollmentService.RequestResult.Rejected.class);
    }
}
