package com.setu.learning.application;

import com.setu.enrollment.application.EnrollmentService;
import com.setu.identity.TestAuthentication;
import com.setu.learning.domain.LearningProgressState;
import com.setu.learning.domain.LessonType;
import com.setu.testsupport.Day10TestFixtures;
import com.setu.testsupport.Day7TestFixtures;
import com.setu.testsupport.Day8TestFixtures;
import com.setu.testsupport.Day9TestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What a student can do in a course after it is over. A final grade completes the enrollment (docs/state-machines.md #11); the student keeps reading the
 * course they took, and cannot add anything to it. A student who dropped, or never enrolled, has neither. Not {@code @Transactional}, for the reason in
 * {@code LessonTests}.
 */
@SpringBootTest
class CompletedStudentAccessTests {

    @Autowired
    Day7TestFixtures fixtures;
    @Autowired
    Day8TestFixtures day8Fixtures;
    @Autowired
    Day9TestFixtures day9Fixtures;
    @Autowired
    Day10TestFixtures day10Fixtures;
    @Autowired
    LessonService lessonService;
    @Autowired
    CourseModuleService courseModuleService;
    @Autowired
    AssignmentService assignmentService;
    @Autowired
    LearningProgressService learningProgressService;
    @Autowired
    EnrollmentService enrollmentService;
    @Autowired
    JdbcClient jdbc;

    @AfterEach
    void clearSecurityContext() {
        TestAuthentication.clear();
    }

    private record Course(Day7TestFixtures.Tenant tenant, UUID programId, UUID curriculumId, UUID sectionId, UUID moduleId, UUID lessonId,
                          Day9TestFixtures.Faculty faculty) {
    }

    private Course setUp(String prefix) {
        var tenant = fixtures.provisionTenant(prefix);
        UUID dept = fixtures.provisionDepartment(tenant, prefix + "D");
        UUID programId = fixtures.provisionProgram(tenant, dept, prefix + "P");
        UUID curriculumId = fixtures.provisionCurriculum(tenant, programId, "v1", java.time.LocalDate.of(2025, 1, 1), null);
        UUID courseId = fixtures.provisionCourse(tenant, dept, prefix + "C");
        UUID periodId = day8Fixtures.provisionOpenPeriod(tenant, prefix + "PR");
        UUID building = fixtures.provisionBuilding(tenant, prefix + "B");
        UUID room = fixtures.provisionRoom(tenant, building, "101", 40);
        UUID sectionId = day8Fixtures.provisionOpenSection(tenant, courseId, periodId, "S1", 30, room);
        var faculty = day9Fixtures.provisionFacultyAssignedToSection(tenant, sectionId, prefix + "F");
        UUID moduleId = day10Fixtures.provisionModule(faculty.userId(), sectionId, "Week 1", 1);
        TestAuthentication.authenticateAs(faculty.userId());
        UUID lessonId = ((LessonService.CreateResult.Success) lessonService.create(moduleId, "Intro", LessonType.VIDEO, "s3://x", 1, 10)).lessonId();
        TestAuthentication.clear();
        return new Course(tenant, programId, curriculumId, sectionId, moduleId, lessonId, faculty);
    }

    private record Learner(Day8TestFixtures.Student student, UUID enrollmentId) {
    }

    private Learner enroll(Course c, String tag) {
        var student = day8Fixtures.provisionStudentWithActiveProgram(c.tenant, c.programId, c.curriculumId, tag);
        UUID enrollmentId = day9Fixtures.enrollStudent(student, c.sectionId);
        return new Learner(student, enrollmentId);
    }

    private void completeWithFinalGrade(Course c, Learner l) {
        // the state the final-grade consumer leaves an enrollment in (docs/state-machines.md #11); that consumer is tested in the enrollment package
        jdbc.sql("UPDATE enrollment.enrollments SET status = 'COMPLETED', final_status = 'B' WHERE id = :id").param("id", l.enrollmentId()).update();
    }

    @Test
    void aStudentWhoFinishedTheCourseStillReadsItsModulesAndLessons() {
        var c = setUp("CS1");
        var learner = enroll(c, "CS1S");
        completeWithFinalGrade(c, learner);
        TestAuthentication.authenticateAs(learner.student().userId());

        assertThat(courseModuleService.listForSection(c.sectionId, c.tenant.tenantId())).extracting(m -> m.id()).contains(c.moduleId);
        assertThat(lessonService.listForModule(c.moduleId)).extracting(l -> l.id()).contains(c.lessonId);
        assertThat(lessonService.get(c.lessonId)).isPresent();
    }

    @Test
    void aStudentWhoFinishedTheCourseStillSeesItsPublishedAssignments() {
        var c = setUp("CS2");
        var learner = enroll(c, "CS2S");
        completeWithFinalGrade(c, learner);
        TestAuthentication.authenticateAs(learner.student().userId());

        // read access is allowed (this does not throw); what it returns is the published assignments only, as for an enrolled student
        assertThat(assignmentService.listForSection(c.sectionId, c.tenant.tenantId())).isNotNull();
    }

    @Test
    void aStudentWhoFinishedTheCourseCannotRecordNewProgressInIt() {
        var c = setUp("CS3");
        var learner = enroll(c, "CS3S");
        completeWithFinalGrade(c, learner);
        TestAuthentication.authenticateAs(learner.student().userId());

        var result = learningProgressService.record(c.lessonId, LearningProgressState.COMPLETED, new BigDecimal("100"), null);

        assertThat(result).as("the course is over: nothing can be added to it").isNotInstanceOf(LearningProgressService.RecordResult.Success.class);
    }

    @Test
    void aStudentStillInTheCourseCanRecordProgress() {
        var c = setUp("CS4");
        var learner = enroll(c, "CS4S");
        TestAuthentication.authenticateAs(learner.student().userId());

        var result = learningProgressService.record(c.lessonId, LearningProgressState.COMPLETED, new BigDecimal("100"), null);

        assertThat(result).isInstanceOf(LearningProgressService.RecordResult.Success.class);
    }

    @Test
    void aStudentWhoDroppedTheCourseReadsNothingOfIt() {
        var c = setUp("CS5");
        var learner = enroll(c, "CS5S");
        TestAuthentication.authenticateAs(learner.student().userId());
        enrollmentService.drop(learner.enrollmentId(), "left the course");
        TestAuthentication.authenticateAs(learner.student().userId());

        assertThatThrownBy(() -> lessonService.listForModule(c.moduleId)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> courseModuleService.listForSection(c.sectionId, c.tenant.tenantId())).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void aStudentWhoNeverEnrolledReadsNothingOfIt() {
        var c = setUp("CS6");
        var outsider = day8Fixtures.provisionStudentWithActiveProgram(c.tenant, c.programId, c.curriculumId, "CS6S");
        TestAuthentication.authenticateAs(outsider.userId());

        assertThatThrownBy(() -> lessonService.listForModule(c.moduleId)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> courseModuleService.listForSection(c.sectionId, c.tenant.tenantId())).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void aFinishedStudentOfOneCourseReadsNothingOfAnother() {
        var c = setUp("CS7");
        var other = setUp("CS8");
        var learner = enroll(c, "CS7S");
        completeWithFinalGrade(c, learner);
        TestAuthentication.authenticateAs(learner.student().userId());

        assertThatThrownBy(() -> lessonService.listForModule(other.moduleId)).isInstanceOf(AccessDeniedException.class);
    }
}
