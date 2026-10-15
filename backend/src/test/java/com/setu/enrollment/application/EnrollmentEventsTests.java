package com.setu.enrollment.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.setu.identity.TestAuthentication;
import com.setu.testsupport.Day7TestFixtures;
import com.setu.testsupport.Day8TestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Academic service tells the rest of the system who is enrolled where through outbox events (canonical spec §8; docs/kafka-event-catalog.md):
 * StudentEnrolled, StudentWaitlisted, WaitlistConverted, EnrollmentDropped. Learning, Attendance, Student Success, Record and Notification
 * rely on these to keep their own view of enrollments once they are separate services (Sprint 6), so each transition must emit exactly
 * the right events, in the same transaction, and a refused request must emit none.
 */
@SpringBootTest
class EnrollmentEventsTests {

    @Autowired
    Day7TestFixtures fixtures;
    @Autowired
    Day8TestFixtures day8Fixtures;
    @Autowired
    EnrollmentService enrollmentService;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    ObjectMapper objectMapper;

    @AfterEach
    void clearSecurityContext() {
        TestAuthentication.clear();
    }

    private record Scenario(Day7TestFixtures.Tenant tenant, UUID programId, UUID curriculumId, UUID sectionId) {
    }

    private Scenario setUp(String prefix, int capacity) {
        var tenant = fixtures.provisionTenant(prefix);
        UUID dept = fixtures.provisionDepartment(tenant, prefix + "D");
        UUID programId = fixtures.provisionProgram(tenant, dept, prefix + "P");
        UUID curriculumId = fixtures.provisionCurriculum(tenant, programId, "v1", LocalDate.of(2025, 1, 1), null);
        UUID courseId = fixtures.provisionCourse(tenant, dept, prefix + "C");
        UUID periodId = day8Fixtures.provisionOpenPeriod(tenant, prefix + "PR");
        UUID sectionId = day8Fixtures.provisionOpenSection(tenant, courseId, periodId, "S1", capacity, null);
        return new Scenario(tenant, programId, curriculumId, sectionId);
    }

    private EnrollmentService.RequestResult request(Day8TestFixtures.Student student, UUID sectionId) {
        TestAuthentication.authenticateAs(student.userId());
        try {
            return enrollmentService.request(student.studentId(), sectionId);
        } finally {
            TestAuthentication.clear();
        }
    }

    private void drop(Day8TestFixtures.Student student, UUID enrollmentId) {
        TestAuthentication.authenticateAs(student.userId());
        try {
            enrollmentService.drop(enrollmentId, "test");
        } finally {
            TestAuthentication.clear();
        }
    }

    /** The events of one tenant, oldest first, as {@code type:aggregateType} with their payloads. */
    private record Emitted(String type, String aggregateType, UUID aggregateId, JsonNode payload) {
    }

    private List<Emitted> emitted(UUID tenantId, String... types) {
        return jdbc.sql("""
                SELECT event_type, aggregate_type, aggregate_id, payload_json::text FROM governance.outbox_events
                WHERE tenant_id = :t AND event_type IN (:types) ORDER BY occurred_at, id
                """).param("t", tenantId).param("types", List.of(types))
            .query((rs, i) -> {
                try {
                    return new Emitted(rs.getString(1), rs.getString(2), rs.getObject(3, UUID.class), objectMapper.readTree(rs.getString(4)));
                } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                    throw new IllegalStateException(e);
                }
            }).list();
    }

    private static final String[] ALL = {"StudentEnrolled", "StudentWaitlisted", "WaitlistConverted", "EnrollmentDropped"};

    @Test
    void anEnrollmentIntoAnOpenSeatEmitsStudentEnrolled() {
        var s = setUp("EV1", 5);
        var student = day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId(), s.curriculumId(), "EV1S");

        var result = (EnrollmentService.RequestResult.Enrolled) request(student, s.sectionId());

        var events = emitted(s.tenant.tenantId(), ALL);
        assertThat(events).hasSize(1);
        var e = events.get(0);
        assertThat(e.type()).isEqualTo("StudentEnrolled");
        assertThat(e.aggregateType()).isEqualTo("enrollments");
        assertThat(e.aggregateId()).as("ordering key: the enrollment").isEqualTo(result.enrollmentId());
        assertThat(e.payload().get("enrollmentId").asText()).isEqualTo(result.enrollmentId().toString());
        assertThat(e.payload().get("sectionId").asText()).isEqualTo(s.sectionId().toString());
        assertThat(e.payload().get("studentId").asText()).isEqualTo(student.studentId().toString());
        assertThat(e.payload().get("tenantId").asText()).isEqualTo(s.tenant.tenantId().toString());
        assertThat(e.payload().get("viaWaitlist").asBoolean()).isFalse();
    }

    @Test
    void aFullSectionEmitsStudentWaitlistedWithThePositionAndNoStudentEnrolled() {
        var s = setUp("EV2", 1);
        var first = day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId(), s.curriculumId(), "EV2A");
        var second = day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId(), s.curriculumId(), "EV2B");
        request(first, s.sectionId());

        var waitlisted = (EnrollmentService.RequestResult.Waitlisted) request(second, s.sectionId());

        var events = emitted(s.tenant.tenantId(), ALL);
        assertThat(events).extracting(Emitted::type).containsExactly("StudentEnrolled", "StudentWaitlisted");
        var w = events.get(1);
        assertThat(w.payload().get("enrollmentId").asText()).isEqualTo(waitlisted.enrollmentId().toString());
        assertThat(w.payload().get("position").asInt()).isEqualTo(1);
    }

    @Test
    void droppingASeatPromotesTheWaitlistedStudentAndTellsEveryone() {
        var s = setUp("EV3", 1);
        var holder = day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId(), s.curriculumId(), "EV3A");
        var waiting = day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId(), s.curriculumId(), "EV3B");
        var held = (EnrollmentService.RequestResult.Enrolled) request(holder, s.sectionId());
        var queued = (EnrollmentService.RequestResult.Waitlisted) request(waiting, s.sectionId());

        drop(holder, held.enrollmentId());

        var events = emitted(s.tenant.tenantId(), ALL);
        assertThat(events).extracting(Emitted::type)
            .containsExactly("StudentEnrolled", "StudentWaitlisted", "EnrollmentDropped", "WaitlistConverted", "StudentEnrolled");
        var dropped = events.get(2);
        assertThat(dropped.payload().get("enrollmentId").asText()).isEqualTo(held.enrollmentId().toString());
        assertThat(dropped.payload().get("wasEnrolled").asBoolean()).isTrue();
        var converted = events.get(3);
        assertThat(converted.aggregateType()).isEqualTo("waitlist_entries");
        assertThat(converted.aggregateId()).as("ordering key: the section, because promotion order matters per section").isEqualTo(s.sectionId());
        assertThat(converted.payload().get("enrollmentId").asText()).isEqualTo(queued.enrollmentId().toString());
        var promoted = events.get(4);
        assertThat(promoted.payload().get("enrollmentId").asText()).isEqualTo(queued.enrollmentId().toString());
        assertThat(promoted.payload().get("viaWaitlist").asBoolean()).isTrue();
    }

    @Test
    void droppingAWaitlistedEnrollmentEmitsEnrollmentDroppedWithoutASeat() {
        var s = setUp("EV4", 1);
        var holder = day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId(), s.curriculumId(), "EV4A");
        var waiting = day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId(), s.curriculumId(), "EV4B");
        request(holder, s.sectionId());
        var queued = (EnrollmentService.RequestResult.Waitlisted) request(waiting, s.sectionId());

        drop(waiting, queued.enrollmentId());

        var events = emitted(s.tenant.tenantId(), ALL);
        assertThat(events).extracting(Emitted::type).containsExactly("StudentEnrolled", "StudentWaitlisted", "EnrollmentDropped");
        assertThat(events.get(2).payload().get("wasEnrolled").asBoolean()).isFalse();
    }

    @Test
    void aRefusedRequestAndADuplicateEmitNothing() {
        var s = setUp("EV5", 5);
        var student = day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId(), s.curriculumId(), "EV5S");
        request(student, s.sectionId());
        int before = emitted(s.tenant.tenantId(), ALL).size();

        var duplicate = request(student, s.sectionId());

        assertThat(duplicate).isInstanceOf(EnrollmentService.RequestResult.DuplicateActiveEnrollment.class);
        assertThat(emitted(s.tenant.tenantId(), ALL)).as("no event for something that did not happen").hasSize(before);
    }

    @Test
    void everyEventCarriesTheEnvelopeFieldsAndTheTenant() {
        var s = setUp("EV6", 1);
        var a = day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId(), s.curriculumId(), "EV6A");
        var b = day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId(), s.curriculumId(), "EV6B");
        var held = (EnrollmentService.RequestResult.Enrolled) request(a, s.sectionId());
        request(b, s.sectionId());
        drop(a, held.enrollmentId());

        for (var e : emitted(s.tenant.tenantId(), ALL)) {
            assertThat(e.payload().get("eventId").asText()).as(e.type()).isNotBlank();
            assertThat(e.payload().get("tenantId").asText()).as(e.type()).isEqualTo(s.tenant.tenantId().toString());
            assertThat(e.payload().get("occurredAt")).as(e.type()).isNotNull();
            assertThat(e.payload().get("correlationId").asText()).as(e.type()).isNotBlank();
            assertThat(e.payload().get("sectionId").asText()).as(e.type()).isEqualTo(s.sectionId().toString());
        }
    }
}
