package com.setu.learning.application;

import com.setu.identity.TestAuthentication;
import com.setu.learning.domain.LessonResourceType;
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

/** Day 10 §3: lesson_resources — ownership follows the owning lesson. */
// Not @Transactional (Sprint 5D hardening): several Day 7 fixture methods now insert through a REQUIRES_NEW guard
// (see e.g. BuildingInsertGuard), so an uncommitted @Transactional test transaction's own fixture rows would be
// invisible to that guard's separate connection — the same lesson already applied across this codebase's other
// REQUIRES_NEW-touching test classes.
@SpringBootTest
class LessonResourceTests {

    @Autowired
    Day7TestFixtures fixtures;
    @Autowired
    Day8TestFixtures day8Fixtures;
    @Autowired
    Day9TestFixtures day9Fixtures;
    @Autowired
    Day10TestFixtures day10Fixtures;
    @Autowired
    LessonResourceService lessonResourceService;

    @AfterEach
    void clearSecurityContext() {
        TestAuthentication.clear();
    }

    private record Scenario(Day7TestFixtures.Tenant tenant, UUID programId, UUID curriculumId, UUID sectionId,
                             UUID lessonId, Day9TestFixtures.Faculty faculty) {
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
        UUID lessonId = day10Fixtures.provisionLesson(faculty.userId(), moduleId, "Intro", 1);
        return new Scenario(tenant, programId, curriculumId, sectionId, lessonId, faculty);
    }

    @Test
    void assignedFacultyCanAddResource() {
        var s = setUp("LR1");
        TestAuthentication.authenticateAs(s.faculty.userId());

        var result = lessonResourceService.add(s.lessonId, "s3://bucket/slides.pdf", LessonResourceType.DOCUMENT);

        assertThat(result).isInstanceOf(LessonResourceService.AddResult.Success.class);
    }

    @Test
    void enrolledStudentCannotAddResource() {
        var s = setUp("LR2");
        var student = day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId, s.curriculumId, "LR2S");
        day9Fixtures.enrollStudent(student, s.sectionId);
        TestAuthentication.authenticateAs(student.userId());

        assertThatThrownBy(() -> lessonResourceService.add(s.lessonId, "s3://x", LessonResourceType.DOCUMENT))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void enrolledStudentCanListResources() {
        var s = setUp("LR3");
        TestAuthentication.authenticateAs(s.faculty.userId());
        lessonResourceService.add(s.lessonId, "s3://bucket/slides.pdf", LessonResourceType.DOCUMENT);
        TestAuthentication.clear();
        var student = day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId, s.curriculumId, "LR3S");
        day9Fixtures.enrollStudent(student, s.sectionId);
        TestAuthentication.authenticateAs(student.userId());

        var result = lessonResourceService.listForLesson(s.lessonId);

        assertThat(result).hasSize(1);
    }

    @Test
    void assignedFacultyCanRemoveResource() {
        var s = setUp("LR4");
        TestAuthentication.authenticateAs(s.faculty.userId());
        var added = (LessonResourceService.AddResult.Success)
            lessonResourceService.add(s.lessonId, "s3://bucket/slides.pdf", LessonResourceType.DOCUMENT);

        var result = lessonResourceService.remove(added.resourceId());

        assertThat(result).isEqualTo(LessonResourceService.RemoveResult.SUCCESS);
    }

    @Test
    void blankMediaRefIsRejected() {
        var s = setUp("LR5");
        TestAuthentication.authenticateAs(s.faculty.userId());

        var result = lessonResourceService.add(s.lessonId, "  ", LessonResourceType.DOCUMENT);

        assertThat(result).isInstanceOf(LessonResourceService.AddResult.InvalidResourceData.class);
    }
}
