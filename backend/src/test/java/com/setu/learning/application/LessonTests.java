package com.setu.learning.application;

import com.setu.identity.TestAuthentication;
import com.setu.identity.domain.RoleCode;
import com.setu.learning.domain.LessonType;
import com.setu.testsupport.Day10TestFixtures;
import com.setu.testsupport.Day7TestFixtures;
import com.setu.testsupport.Day8TestFixtures;
import com.setu.testsupport.Day9TestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Day 10 §2, §13: lessons CRUD and authorization matrix — including "student edits lesson content -> DENY". */
// Not @Transactional (Sprint 5D hardening): several Day 7 fixture methods now insert through a REQUIRES_NEW guard
// (see e.g. BuildingInsertGuard), so an uncommitted @Transactional test transaction's own fixture rows would be
// invisible to that guard's separate connection — the same lesson already applied across this codebase's other
// REQUIRES_NEW-touching test classes.
@SpringBootTest
class LessonTests {

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

    @AfterEach
    void clearSecurityContext() {
        TestAuthentication.clear();
    }

    private record Scenario(Day7TestFixtures.Tenant tenant, UUID programId, UUID curriculumId, UUID sectionId,
                             UUID moduleId, Day9TestFixtures.Faculty faculty) {
    }

    private Scenario setUp(String prefix) {
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
        return new Scenario(tenant, programId, curriculumId, sectionId, moduleId, faculty);
    }

    @Test
    void assignedFacultyCanCreateLesson() {
        var s = setUp("LS1");
        TestAuthentication.authenticateAs(s.faculty.userId());

        var result = lessonService.create(s.moduleId, "Intro", LessonType.VIDEO, "s3://x", 1, 10);

        assertThat(result).isInstanceOf(LessonService.CreateResult.Success.class);
    }

    @Test
    void unassignedFacultyCannotCreateLesson() {
        var s = setUp("LS2");
        UUID otherFacultyUserId = fixtures.provisionUserWithRole(s.tenant.tenantId(), RoleCode.FACULTY, "LS2-OTHER");
        TestAuthentication.authenticateAs(otherFacultyUserId);

        assertThatThrownBy(() -> lessonService.create(s.moduleId, "Intro", LessonType.VIDEO, "s3://x", 1, 10))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void studentCannotCreateLesson() {
        var s = setUp("LS3");
        var student = day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId, s.curriculumId, "LS3S");
        day9Fixtures.enrollStudent(student, s.sectionId);
        TestAuthentication.authenticateAs(student.userId());

        assertThatThrownBy(() -> lessonService.create(s.moduleId, "Intro", LessonType.VIDEO, "s3://x", 1, 10))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void enrolledStudentCannotEditLessonContent() {
        var s = setUp("LS4");
        UUID lessonId = day10Fixtures.provisionLesson(s.faculty.userId(), s.moduleId, "Intro", 1);
        var student = day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId, s.curriculumId, "LS4S");
        day9Fixtures.enrollStudent(student, s.sectionId);
        TestAuthentication.authenticateAs(student.userId());

        assertThatThrownBy(() -> lessonService.update(lessonId, "Hacked", LessonType.TEXT, "s3://evil", 1, 5))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void enrolledStudentCanReadLesson() {
        var s = setUp("LS5");
        UUID lessonId = day10Fixtures.provisionLesson(s.faculty.userId(), s.moduleId, "Intro", 1);
        var student = day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId, s.curriculumId, "LS5S");
        day9Fixtures.enrollStudent(student, s.sectionId);
        TestAuthentication.authenticateAs(student.userId());

        var result = lessonService.get(lessonId);

        assertThat(result).isPresent();
    }

    @Test
    void assignedFacultyCanDeleteLesson() {
        var s = setUp("LS6");
        UUID lessonId = day10Fixtures.provisionLesson(s.faculty.userId(), s.moduleId, "Intro", 1);
        TestAuthentication.authenticateAs(s.faculty.userId());

        var result = lessonService.delete(lessonId);

        assertThat(result).isEqualTo(LessonService.DeleteResult.SUCCESS);
    }

    @Test
    void sequenceMustBeUniqueWithinModule() {
        var s = setUp("LS7");
        TestAuthentication.authenticateAs(s.faculty.userId());
        lessonService.create(s.moduleId, "Intro", LessonType.VIDEO, "s3://x", 1, 10);

        var result = lessonService.create(s.moduleId, "Intro 2", LessonType.VIDEO, "s3://y", 1, 10);

        assertThat(result).isInstanceOf(LessonService.CreateResult.SequenceAlreadyInUse.class);
    }

    @Test
    void youtubeAndDocumentationLinksAreAcceptedAsLessonContent() {
        var s = setUp("LS8");
        TestAuthentication.authenticateAs(s.faculty.userId());

        var result = lessonService.create(s.moduleId, "CS50P Lecture 0", LessonType.VIDEO,
            "https://www.youtube.com/watch?v=JP7ITIXGpHk", 1, 120);

        assertThat(result).isInstanceOf(LessonService.CreateResult.Success.class);
        var lessonId = ((LessonService.CreateResult.Success) result).lessonId();
        assertThat(lessonService.get(lessonId)).get().extracting(l -> l.contentRef())
            .isEqualTo("https://www.youtube.com/watch?v=JP7ITIXGpHk");
    }

    @Test
    void unsafeLessonContentLinksAreRejectedOnCreateAndUpdate() {
        var s = setUp("LS9");
        TestAuthentication.authenticateAs(s.faculty.userId());
        var created = (LessonService.CreateResult.Success) lessonService.create(s.moduleId, "Intro", LessonType.VIDEO, "s3://x", 1, 10);

        for (String bad : new String[]{"javascript:alert(1)", "http://example.org/plain", "data:text/html;base64,AAAA", "https://a b"}) {
            assertThat(lessonService.create(s.moduleId, "Bad", LessonType.TEXT, bad, 2, 5))
                .as("create " + bad).isInstanceOf(LessonService.CreateResult.InvalidLessonData.class);
            assertThat(lessonService.update(created.lessonId(), "Intro", LessonType.VIDEO, bad, 1, 10))
                .as("update " + bad).isInstanceOf(LessonService.UpdateResult.InvalidLessonData.class);
        }
        assertThat(lessonService.get(created.lessonId())).get().extracting(l -> l.contentRef()).isEqualTo("s3://x");
    }
}
