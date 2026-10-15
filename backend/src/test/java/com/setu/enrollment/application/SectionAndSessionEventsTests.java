package com.setu.enrollment.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.setu.attendance.application.AttendanceSessionService;
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

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Section and attendance-session lifecycle events, which the Learning, Attendance and Student Success services will build their own view
 * from once separate (docs/extraction-audit.md section 7): CourseSectionOpened, CourseSectionCancelled, AttendanceSessionOpened,
 * AttendanceSessionClosed. Each is written in the same transaction as the change, and a refused change writes none.
 */
@SpringBootTest
class SectionAndSessionEventsTests {

    @Autowired
    Day7TestFixtures fixtures;
    @Autowired
    Day8TestFixtures day8Fixtures;
    @Autowired
    Day9TestFixtures day9Fixtures;
    @Autowired
    CourseSectionService courseSectionService;
    @Autowired
    EnrollmentService enrollmentService;
    @Autowired
    AttendanceSessionService attendanceSessionService;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    ObjectMapper objectMapper;

    @AfterEach
    void clearSecurityContext() {
        TestAuthentication.clear();
    }

    private record Emitted(String type, UUID aggregateId, JsonNode payload) {
    }

    private List<Emitted> emitted(UUID tenantId, String... types) {
        return jdbc.sql("SELECT event_type, aggregate_id, payload_json::text FROM governance.outbox_events WHERE tenant_id = :t AND event_type IN (:types) ORDER BY occurred_at, id")
            .param("t", tenantId).param("types", List.of(types))
            .query((rs, i) -> {
                try {
                    return new Emitted(rs.getString(1), rs.getObject(2, UUID.class), objectMapper.readTree(rs.getString(3)));
                } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                    throw new IllegalStateException(e);
                }
            }).list();
    }

    private record Scenario(Day7TestFixtures.Tenant tenant, UUID programId, UUID curriculumId, UUID courseId, UUID periodId, UUID room, UUID sectionId) {
    }

    private Scenario setUp(String prefix, int capacity) {
        var tenant = fixtures.provisionTenant(prefix);
        UUID dept = fixtures.provisionDepartment(tenant, prefix + "D");
        UUID programId = fixtures.provisionProgram(tenant, dept, prefix + "P");
        UUID curriculumId = fixtures.provisionCurriculum(tenant, programId, "v1", LocalDate.of(2025, 1, 1), null);
        UUID courseId = fixtures.provisionCourse(tenant, dept, prefix + "C");
        UUID periodId = day8Fixtures.provisionOpenPeriod(tenant, prefix + "PR");
        UUID building = fixtures.provisionBuilding(tenant, prefix + "B");
        UUID room = fixtures.provisionRoom(tenant, building, "101", 40);
        UUID sectionId = day8Fixtures.provisionOpenSection(tenant, courseId, periodId, "S1", capacity, room);
        return new Scenario(tenant, programId, curriculumId, courseId, periodId, room, sectionId);
    }

    @Test
    void openingASectionEmitsCourseSectionOpenedWithItsCourseAndPeriod() {
        var s = setUp("SE1", 5);

        var events = emitted(s.tenant.tenantId(), "CourseSectionOpened");

        assertThat(events).hasSize(1);
        assertThat(events.get(0).aggregateId()).isEqualTo(s.sectionId);
        assertThat(events.get(0).payload().get("sectionId").asText()).isEqualTo(s.sectionId.toString());
        assertThat(events.get(0).payload().get("courseId").asText()).isEqualTo(s.courseId.toString());
        assertThat(events.get(0).payload().get("periodId").asText()).isEqualTo(s.periodId.toString());
    }

    @Test
    void cancellingASectionListsTheEnrollmentsItLeftBehind() {
        var s = setUp("SE2", 1);
        var enrolled = day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId, s.curriculumId, "SE2A");
        var waiting = day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId, s.curriculumId, "SE2B");
        UUID enrolledId = day9Fixtures.enrollStudent(enrolled, s.sectionId);
        TestAuthentication.authenticateAs(waiting.userId());
        var waitlisted = (EnrollmentService.RequestResult.Waitlisted) enrollmentService.request(waiting.studentId(), s.sectionId);
        TestAuthentication.clear();

        TestAuthentication.authenticateAs(s.tenant.adminUserId());
        assertThat(courseSectionService.changeStatus(s.sectionId, CourseSectionStatus.CANCELLED)).isEqualTo(CourseSectionService.StatusChangeResult.SUCCESS);

        var events = emitted(s.tenant.tenantId(), "CourseSectionCancelled");
        assertThat(events).hasSize(1);
        List<String> affected = new java.util.ArrayList<>();
        events.get(0).payload().get("affectedEnrollmentIds").forEach(n -> affected.add(n.asText()));
        assertThat(affected).containsExactlyInAnyOrder(enrolledId.toString(), waitlisted.enrollmentId().toString());
    }

    @Test
    void closingAndRefusedTransitionsEmitNothingFurther() {
        var s = setUp("SE3", 5);
        TestAuthentication.authenticateAs(s.tenant.adminUserId());

        assertThat(courseSectionService.changeStatus(s.sectionId, CourseSectionStatus.CLOSED)).isEqualTo(CourseSectionService.StatusChangeResult.SUCCESS);
        assertThat(courseSectionService.changeStatus(s.sectionId, CourseSectionStatus.DRAFT)).isEqualTo(CourseSectionService.StatusChangeResult.INVALID_TRANSITION);

        assertThat(emitted(s.tenant.tenantId(), "CourseSectionOpened", "CourseSectionCancelled")).extracting(Emitted::type).containsExactly("CourseSectionOpened");
    }

    @Test
    void anAttendanceSessionEmitsOpenedThenClosedWithTheAbsencesTheSystemMarked() {
        var s = setUp("SE4", 5);
        UUID meetingId = day9Fixtures.provisionMeetingPattern(s.tenant, s.sectionId, s.room);
        var faculty = day9Fixtures.provisionFacultyAssignedToSection(s.tenant, s.sectionId, "SE4F");
        var student = day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId, s.curriculumId, "SE4S");
        day9Fixtures.enrollStudent(student, s.sectionId);

        TestAuthentication.authenticateAs(faculty.userId());
        var opened = (AttendanceSessionService.OpenResult.Success) attendanceSessionService.open(s.sectionId, meetingId, Instant.now().plus(1, ChronoUnit.HOURS));
        var closed = (AttendanceSessionService.CloseResult.Success) attendanceSessionService.close(opened.sessionId());

        var events = emitted(s.tenant.tenantId(), "AttendanceSessionOpened", "AttendanceSessionClosed");
        assertThat(events).extracting(Emitted::type).containsExactly("AttendanceSessionOpened", "AttendanceSessionClosed");
        assertThat(events.get(0).aggregateId()).isEqualTo(opened.sessionId());
        assertThat(events.get(0).payload().get("meetingId").asText()).isEqualTo(meetingId.toString());
        assertThat(events.get(1).payload().get("sectionId").asText()).isEqualTo(s.sectionId.toString());
        assertThat(events.get(1).payload().get("absentRecordsGenerated").asInt()).isEqualTo(closed.absentRecordsGenerated()).isEqualTo(1);
    }

    @Test
    void aRefusedOpenAndASecondCloseEmitNothing() {
        var s = setUp("SE5", 5);
        UUID meetingId = day9Fixtures.provisionMeetingPattern(s.tenant, s.sectionId, s.room);
        var faculty = day9Fixtures.provisionFacultyAssignedToSection(s.tenant, s.sectionId, "SE5F");
        TestAuthentication.authenticateAs(faculty.userId());
        var opened = (AttendanceSessionService.OpenResult.Success) attendanceSessionService.open(s.sectionId, meetingId, Instant.now().plus(1, ChronoUnit.HOURS));
        assertThat(attendanceSessionService.open(s.sectionId, meetingId, Instant.now().plus(1, ChronoUnit.HOURS)))
            .as("a second open for the same meeting is refused").isInstanceOf(AttendanceSessionService.OpenResult.SessionAlreadyOpenForMeeting.class);
        attendanceSessionService.close(opened.sessionId());
        assertThat(attendanceSessionService.close(opened.sessionId())).isInstanceOf(AttendanceSessionService.CloseResult.InvalidTransition.class);

        assertThat(emitted(s.tenant.tenantId(), "AttendanceSessionOpened", "AttendanceSessionClosed")).hasSize(2);
    }
}
