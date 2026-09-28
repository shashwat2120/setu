package com.setu.enrollment.application;

import com.setu.catalog.application.CoursePrerequisiteService;
import com.setu.catalog.domain.PrerequisiteRuleType;
import com.setu.guardianaccess.application.GuardianAccessService;
import com.setu.identity.TestAuthentication;
import com.setu.testsupport.Day10TestFixtures;
import com.setu.testsupport.Day7TestFixtures;
import com.setu.testsupport.Day8TestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;

import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Day 8 §16 core enrollment invariant-chain and state-machine test matrix. */
// Not @Transactional (Sprint 5D hardening): several Day 7 fixture methods now insert through a REQUIRES_NEW guard
// (see e.g. BuildingInsertGuard), so an uncommitted @Transactional test transaction's own fixture rows would be
// invisible to that guard's separate connection — the same lesson already applied across this codebase's other
// REQUIRES_NEW-touching test classes.
@SpringBootTest
class EnrollmentTests {

    @Autowired
    Day7TestFixtures fixtures;
    @Autowired
    Day8TestFixtures day8Fixtures;
    @Autowired
    EnrollmentService enrollmentService;
    @Autowired
    CoursePrerequisiteService coursePrerequisiteService;
    @Autowired
    com.setu.catalog.application.MeetingPatternService meetingPatternService;
    @Autowired
    Day10TestFixtures day10Fixtures;
    @Autowired
    GuardianAccessService guardianAccessService;

    @AfterEach
    void clearSecurityContext() {
        TestAuthentication.clear();
    }

    private record Scenario(Day7TestFixtures.Tenant tenant, UUID programId, UUID curriculumId, UUID courseId,
                             UUID periodId, UUID sectionId, Day8TestFixtures.Student student) {
    }

    private Scenario setUp(String prefix, int capacity) {
        var tenant = fixtures.provisionTenant(prefix);
        UUID dept = fixtures.provisionDepartment(tenant, prefix + "D");
        UUID programId = fixtures.provisionProgram(tenant, dept, prefix + "P");
        UUID curriculumId = fixtures.provisionCurriculum(tenant, programId, "v1", LocalDate.of(2025, 1, 1), null);
        UUID courseId = fixtures.provisionCourse(tenant, dept, prefix + "C");
        UUID periodId = day8Fixtures.provisionOpenPeriod(tenant, prefix + "PR");
        UUID sectionId = day8Fixtures.provisionOpenSection(tenant, courseId, periodId, "S1", capacity, null);
        var student = day8Fixtures.provisionStudentWithActiveProgram(tenant, programId, curriculumId, prefix + "S1");
        return new Scenario(tenant, programId, curriculumId, courseId, periodId, sectionId, student);
    }

    @Test
    void validEnrollmentRequestSucceeds() {
        var s = setUp("ENR1", 10);
        TestAuthentication.authenticateAs(s.student.userId());

        var result = enrollmentService.request(s.student.studentId(), s.sectionId);

        assertThat(result).isInstanceOf(EnrollmentService.RequestResult.Enrolled.class);
    }

    @Test
    void studentCannotEnrollAnotherStudent() {
        var s = setUp("ENR2", 10);
        var otherStudent = day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId, s.curriculumId, "ENR2S2");
        TestAuthentication.authenticateAs(otherStudent.userId());

        assertThatThrownBy(() -> enrollmentService.request(s.student.studentId(), s.sectionId))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void collegeAdminCanEnrollOnBehalfOfStudent() {
        var s = setUp("ENR3", 10);
        TestAuthentication.authenticateAs(s.tenant.adminUserId());

        var result = enrollmentService.request(s.student.studentId(), s.sectionId);

        assertThat(result).isInstanceOf(EnrollmentService.RequestResult.Enrolled.class);
    }

    @Test
    void crossTenantEnrollmentIsDenied() {
        var s = setUp("ENR4A", 10);
        var otherTenant = fixtures.provisionTenant("ENR4B");
        TestAuthentication.authenticateAs(otherTenant.adminUserId());

        assertThatThrownBy(() -> enrollmentService.request(s.student.studentId(), s.sectionId))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void duplicateActiveEnrollmentIsRejected() {
        var s = setUp("ENR5", 10);
        TestAuthentication.authenticateAs(s.student.userId());
        enrollmentService.request(s.student.studentId(), s.sectionId);

        var result = enrollmentService.request(s.student.studentId(), s.sectionId);

        assertThat(result).isInstanceOf(EnrollmentService.RequestResult.DuplicateActiveEnrollment.class);
    }

    @Test
    void fullCapacitySectionWaitlistsInsteadOfRejecting() {
        var s = setUp("ENR6", 1);
        var studentB = day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId, s.curriculumId, "ENR6S2");
        TestAuthentication.authenticateAs(s.student.userId());
        enrollmentService.request(s.student.studentId(), s.sectionId);
        TestAuthentication.authenticateAs(studentB.userId());

        var result = enrollmentService.request(studentB.studentId(), s.sectionId);

        assertThat(result).isInstanceOf(EnrollmentService.RequestResult.Waitlisted.class);
        assertThat(((EnrollmentService.RequestResult.Waitlisted) result).position()).isEqualTo(1);
    }

    @Test
    void prerequisiteNotSatisfiedRejectsEnrollment() {
        var s = setUp("ENR7", 10);
        UUID prereqCourse = fixtures.provisionCourse(s.tenant, fixtures.provisionDepartment(s.tenant, "ENR7D2"), "ENR7-PRE");
        TestAuthentication.authenticateAs(s.tenant.adminUserId());
        coursePrerequisiteService.addPrerequisite(s.courseId, prereqCourse, PrerequisiteRuleType.REQUIRED, null);
        TestAuthentication.authenticateAs(s.student.userId());

        var result = enrollmentService.request(s.student.studentId(), s.sectionId);

        assertThat(result).isInstanceOf(EnrollmentService.RequestResult.Rejected.class);
        assertThat(((EnrollmentService.RequestResult.Rejected) result).reason())
            .isEqualTo(EnrollmentService.RejectReason.PREREQUISITE_NOT_SATISFIED);
    }

    @Test
    void scheduleConflictRejectsEnrollment() {
        var s = setUp("ENR8", 10);
        UUID building = fixtures.provisionBuilding(s.tenant, "ENR8B");
        // Two DIFFERENT rooms — this test is the STUDENT schedule conflict (Day 8 §11), not the room-booking conflict (Day 7's excl_meeting_patterns_room_overlap); using the same room here would trip that unrelated DB constraint instead.
        UUID roomA = fixtures.provisionRoom(s.tenant, building, "101", 40);
        UUID roomB = fixtures.provisionRoom(s.tenant, building, "102", 40);
        UUID otherCourse = fixtures.provisionCourse(s.tenant, fixtures.provisionDepartment(s.tenant, "ENR8D2"), "ENR8-C2");
        UUID otherSection = day8Fixtures.provisionOpenSection(s.tenant, otherCourse, s.periodId, "S2", 10, roomB);
        TestAuthentication.authenticateAs(s.tenant.adminUserId());
        meetingPatternService.create(s.sectionId, (short) 1, java.time.LocalTime.of(9, 0), java.time.LocalTime.of(10, 0), roomA, null, null);
        meetingPatternService.create(otherSection, (short) 1, java.time.LocalTime.of(9, 30), java.time.LocalTime.of(10, 30), roomB, null, null);
        TestAuthentication.authenticateAs(s.student.userId());
        enrollmentService.request(s.student.studentId(), otherSection);

        var result = enrollmentService.request(s.student.studentId(), s.sectionId);

        assertThat(result).isInstanceOf(EnrollmentService.RequestResult.Rejected.class);
        assertThat(((EnrollmentService.RequestResult.Rejected) result).reason())
            .isEqualTo(EnrollmentService.RejectReason.SCHEDULE_CONFLICT);
    }

    @Test
    void dropThenReopenAllowsRetry() {
        var s = setUp("ENR9", 10);
        TestAuthentication.authenticateAs(s.student.userId());
        UUID enrollmentId = ((EnrollmentService.RequestResult.Enrolled)
            enrollmentService.request(s.student.studentId(), s.sectionId)).enrollmentId();
        enrollmentService.drop(enrollmentId, "changed my mind");

        var result = enrollmentService.request(s.student.studentId(), s.sectionId);

        assertThat(result).isInstanceOf(EnrollmentService.RequestResult.Enrolled.class);
        assertThat(((EnrollmentService.RequestResult.Enrolled) result).enrollmentId()).isEqualTo(enrollmentId);
    }

    @Test
    void dropOfNonActiveEnrollmentIsRejected() {
        var s = setUp("ENR10", 10);
        TestAuthentication.authenticateAs(s.student.userId());
        UUID enrollmentId = ((EnrollmentService.RequestResult.Enrolled)
            enrollmentService.request(s.student.studentId(), s.sectionId)).enrollmentId();
        enrollmentService.drop(enrollmentId, "first drop");

        var result = enrollmentService.drop(enrollmentId, "second drop");

        assertThat(result).isInstanceOf(EnrollmentService.DropResult.InvalidTransition.class);
    }

    @Test
    void actionHistoryIsRecordedForEveryTransition() {
        var s = setUp("ENR11", 10);
        TestAuthentication.authenticateAs(s.student.userId());
        UUID enrollmentId = ((EnrollmentService.RequestResult.Enrolled)
            enrollmentService.request(s.student.studentId(), s.sectionId)).enrollmentId();
        enrollmentService.drop(enrollmentId, "done");

        var history = enrollmentService.history(enrollmentId);

        assertThat(history).extracting(a -> a.action())
            .containsExactly("ENROLLMENT_REQUESTED", "ENROLLMENT_APPROVED", "ENROLLMENT_DROPPED");
    }

    // --- Day 16 §guardian: listForStudent had no delegation bypass at all — a guardian with a real, active
    // VIEW_SCHEDULE grant got the same blanket AccessDeniedException as a stranger, breaking the very first
    // step of the whole guardian data-viewing chain (grades/attendance/learning progress are all keyed by
    // enrollmentId, which this is the only way to discover for a student who isn't the caller).

    @Test
    void guardianWithActiveViewScheduleDelegationCanListTheStudentsEnrollments() {
        var s = setUp("ENR12", 10);
        TestAuthentication.authenticateAs(s.student.userId());
        enrollmentService.request(s.student.studentId(), s.sectionId);
        TestAuthentication.clear();

        var guardian = day10Fixtures.provisionGuardian(s.tenant, "ENR12G");
        TestAuthentication.authenticateAs(s.tenant.adminUserId());
        guardianAccessService.grantAccess(s.student.studentId(), guardian.guardianId(), null, Set.of("VIEW_SCHEDULE"));
        TestAuthentication.clear();

        TestAuthentication.authenticateAs(guardian.guardianUserId());
        var page = enrollmentService.listForStudent(s.student.studentId(), PageRequest.of(0, 10));

        assertThat(page.getContent()).hasSize(1);
    }

    @Test
    void guardianWithoutAScheduleGrantCannotListTheStudentsEnrollments() {
        var s = setUp("ENR13", 10);
        TestAuthentication.authenticateAs(s.student.userId());
        enrollmentService.request(s.student.studentId(), s.sectionId);
        TestAuthentication.clear();

        var guardian = day10Fixtures.provisionGuardian(s.tenant, "ENR13G");
        // Deliberately no grant at all.

        TestAuthentication.authenticateAs(guardian.guardianUserId());

        assertThatThrownBy(() -> enrollmentService.listForStudent(s.student.studentId(), PageRequest.of(0, 10)))
            .isInstanceOf(AccessDeniedException.class);
    }
}
