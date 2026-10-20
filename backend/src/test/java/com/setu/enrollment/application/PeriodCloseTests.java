package com.setu.enrollment.application;

import com.setu.catalog.application.AcademicPeriodService;
import com.setu.catalog.domain.AcademicPeriod.PeriodStatus;
import com.setu.enrollment.domain.CourseSectionStatus;
import com.setu.identity.TestAuthentication;
import com.setu.testsupport.Day7TestFixtures;
import com.setu.testsupport.Day8TestFixtures;
import com.setu.testsupport.Day9TestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A period closes only when every section in it is CLOSED or CANCELLED and no student in it is still ENROLLED (docs/state-machines.md #6), and its close
 * is announced exactly once. A student leaves ENROLLED by a final grade completing the enrollment or by being dropped, so closing a period never decides
 * anybody's result. Not {@code @Transactional}: a blocked close rolls back its own transaction and the test must see that it did.
 */
@SpringBootTest
class PeriodCloseTests {

    @Autowired
    Day7TestFixtures fixtures;
    @Autowired
    Day8TestFixtures day8Fixtures;
    @Autowired
    Day9TestFixtures day9Fixtures;
    @Autowired
    AcademicPeriodService periodService;
    @Autowired
    CourseSectionService sectionService;
    @Autowired
    EnrollmentService enrollmentService;
    @Autowired
    JdbcClient jdbc;

    @AfterEach
    void clear() {
        TestAuthentication.clear();
    }

    private record Scenario(Day7TestFixtures.Tenant tenant, UUID periodId, UUID sectionId, UUID enrollmentId, Day8TestFixtures.Student student, UUID courseId, UUID roomId) {
    }

    /** A period IN_PROGRESS with one OPEN section and one student ENROLLED in it. */
    private Scenario setUp(String prefix) {
        var tenant = fixtures.provisionTenant(prefix);
        UUID dept = fixtures.provisionDepartment(tenant, prefix + "D");
        UUID courseId = fixtures.provisionCourse(tenant, dept, prefix + "C");
        UUID periodId = day8Fixtures.provisionOpenPeriod(tenant, prefix + "PR");
        UUID building = fixtures.provisionBuilding(tenant, prefix + "B");
        UUID room = fixtures.provisionRoom(tenant, building, "101", 40);
        UUID sectionId = day8Fixtures.provisionOpenSection(tenant, courseId, periodId, "S1", 30, room);
        UUID programId = fixtures.provisionProgram(tenant, dept, prefix + "P");
        UUID curriculumId = fixtures.provisionCurriculum(tenant, programId, "v1", java.time.LocalDate.of(2025, 1, 1), null);
        var student = day8Fixtures.provisionStudentWithActiveProgram(tenant, programId, curriculumId, prefix + "S");
        UUID enrollmentId = day9Fixtures.enrollStudent(student, sectionId);
        asAdmin(tenant, () -> {
            periodService.transitionTo(periodId, PeriodStatus.REGISTRATION_CLOSED);
            periodService.transitionTo(periodId, PeriodStatus.IN_PROGRESS);
        });
        return new Scenario(tenant, periodId, sectionId, enrollmentId, student, courseId, room);
    }

    private void asAdmin(Day7TestFixtures.Tenant tenant, Runnable action) {
        TestAuthentication.authenticateAs(tenant.adminUserId());
        try {
            action.run();
        } finally {
            TestAuthentication.clear();
        }
    }

    private AcademicPeriodService.TransitionResult close(Scenario s, boolean closeOpenSections) {
        TestAuthentication.authenticateAs(s.tenant.adminUserId());
        try {
            return periodService.transitionTo(s.periodId, PeriodStatus.CLOSED, closeOpenSections);
        } finally {
            TestAuthentication.clear();
        }
    }

    private String periodStatus(UUID periodId) {
        return jdbc.sql("select status from catalog.academic_periods where id = ?").param(periodId).query(String.class).single();
    }

    private String sectionStatus(UUID sectionId) {
        return jdbc.sql("select status from enrollment.course_sections where id = ?").param(sectionId).query(String.class).single();
    }

    private long closedEvents(UUID periodId) {
        return jdbc.sql("select count(*) from governance.outbox_events where event_type = 'PeriodClosed' and aggregate_id = ?")
            .param(periodId).query(Long.class).single();
    }

    @Test
    void aPeriodWithAnOpenSectionDoesNotClose() {
        var s = setUp("PCL1");

        assertThat(close(s, false)).isEqualTo(AcademicPeriodService.TransitionResult.CLOSE_BLOCKED);

        assertThat(periodStatus(s.periodId)).isEqualTo("IN_PROGRESS");
        assertThat(closedEvents(s.periodId)).isZero();
    }

    @Test
    void theCheckNamesTheOpenSectionAndTheStudentsStillEnrolled() {
        var s = setUp("PCL2");
        TestAuthentication.authenticateAs(s.tenant.adminUserId());

        var blockers = periodService.closeCheck(s.periodId).orElseThrow();

        assertThat(blockers.none()).isFalse();
        assertThat(blockers.openSections()).singleElement().satisfies(o -> {
            assertThat(o.sectionId()).isEqualTo(s.sectionId);
            assertThat(o.status()).isEqualTo("OPEN");
        });
        assertThat(blockers.ungradedSections()).singleElement().satisfies(u -> {
            assertThat(u.sectionId()).isEqualTo(s.sectionId);
            assertThat(u.enrolledStudents()).isEqualTo(1);
        });
    }

    @Test
    void closingTheSectionsIsNotEnoughWhileAStudentIsStillEnrolled() {
        var s = setUp("PCL3");

        assertThat(close(s, true)).isEqualTo(AcademicPeriodService.TransitionResult.CLOSE_BLOCKED);

        // the blocked close rolled back with the section changes it had made: nothing half-done
        assertThat(periodStatus(s.periodId)).isEqualTo("IN_PROGRESS");
        assertThat(sectionStatus(s.sectionId)).isEqualTo("OPEN");
        assertThat(closedEvents(s.periodId)).isZero();
    }

    @Test
    void aStudentWhoWasDroppedNoLongerBlocksTheClose() {
        var s = setUp("PCL4");
        TestAuthentication.authenticateAs(s.student.userId());
        enrollmentService.drop(s.enrollmentId, "left the course");
        TestAuthentication.clear();

        assertThat(close(s, true)).isEqualTo(AcademicPeriodService.TransitionResult.SUCCESS);

        assertThat(periodStatus(s.periodId)).isEqualTo("CLOSED");
        assertThat(sectionStatus(s.sectionId)).isEqualTo(CourseSectionStatus.CLOSED.name());
        assertThat(closedEvents(s.periodId)).isEqualTo(1);
    }

    @Test
    void aStudentWhoseFinalGradeCompletedTheEnrollmentNoLongerBlocksTheClose() {
        var s = setUp("PCL5");
        jdbc.sql("update enrollment.enrollments set status = 'COMPLETED', final_status = 'B' where id = ?").param(s.enrollmentId).update();

        assertThat(close(s, true)).isEqualTo(AcademicPeriodService.TransitionResult.SUCCESS);

        assertThat(periodStatus(s.periodId)).isEqualTo("CLOSED");
        // the close did not touch the student's result
        assertThat(jdbc.sql("select final_status from enrollment.enrollments where id = ?").param(s.enrollmentId).query(String.class).single())
            .isEqualTo("B");
    }

    @Test
    void closingTheSectionsByHandAlsoWorksWithoutTheFlag() {
        var s = setUp("PCL6");
        jdbc.sql("update enrollment.enrollments set status = 'COMPLETED', final_status = 'A' where id = ?").param(s.enrollmentId).update();
        asAdmin(s.tenant, () -> sectionService.changeStatus(s.sectionId, CourseSectionStatus.CLOSED));

        assertThat(close(s, false)).isEqualTo(AcademicPeriodService.TransitionResult.SUCCESS);
        assertThat(periodStatus(s.periodId)).isEqualTo("CLOSED");
    }

    @Test
    void aDraftSectionIsCancelledByTheFlagBecauseItWasNeverOpened() {
        var s = setUp("PCL7");
        jdbc.sql("update enrollment.enrollments set status = 'COMPLETED', final_status = 'C' where id = ?").param(s.enrollmentId).update();
        // a second section, still DRAFT, in the same period
        UUID draft = fixtures.provisionSection(s.tenant, s.courseId, s.periodId, "S2", 10, s.roomId);
        assertThat(sectionStatus(draft)).isEqualTo("DRAFT");

        assertThat(close(s, true)).isEqualTo(AcademicPeriodService.TransitionResult.SUCCESS);

        assertThat(sectionStatus(draft)).isEqualTo("CANCELLED");
        assertThat(sectionStatus(s.sectionId)).isEqualTo("CLOSED");
    }

    @Test
    void closingIsAnnouncedOnceWithTheSchemaCatalogFreezes() {
        var s = setUp("PCL8");
        jdbc.sql("update enrollment.enrollments set status = 'COMPLETED', final_status = 'B' where id = ?").param(s.enrollmentId).update();

        close(s, true);

        assertThat(closedEvents(s.periodId)).isEqualTo(1);
        assertThat(jdbc.sql("select payload_json::text from governance.outbox_events where event_type = 'PeriodClosed' and aggregate_id = ?")
            .param(s.periodId).query(String.class).single()).contains(s.periodId.toString()).contains("periodCode");
        // a second close attempt is refused as an invalid transition and announces nothing more
        assertThat(close(s, true)).isEqualTo(AcademicPeriodService.TransitionResult.INVALID_TRANSITION);
        assertThat(closedEvents(s.periodId)).isEqualTo(1);
    }

    @Test
    void onlyTheCalendarManagerMayAskOrClose() {
        var s = setUp("PCL9");
        TestAuthentication.authenticateAs(s.student.userId());

        assertThatThrownBy(() -> periodService.closeCheck(s.periodId)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> periodService.transitionTo(s.periodId, PeriodStatus.CLOSED, true)).isInstanceOf(AccessDeniedException.class);
        TestAuthentication.clear();
        assertThat(periodStatus(s.periodId)).isEqualTo("IN_PROGRESS");
    }
}
