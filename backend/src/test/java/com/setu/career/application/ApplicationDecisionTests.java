package com.setu.career.application;

import com.setu.identity.TestAuthentication;
import com.setu.testsupport.Day12TestFixtures;
import com.setu.testsupport.Day7TestFixtures;
import com.setu.testsupport.Day8TestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A placement officer's decision on an application carries a reason the student is shown (required to reject), is recorded with when it was made,
 * and is announced to the student through the outbox in the same transaction. Deliberately not {@code @Transactional}: applying uses its own transaction.
 */
@SpringBootTest
class ApplicationDecisionTests {

    @Autowired
    Day7TestFixtures fixtures;
    @Autowired
    Day8TestFixtures day8Fixtures;
    @Autowired
    Day12TestFixtures day12Fixtures;
    @Autowired
    JobService jobService;
    @Autowired
    ApplicationService applicationService;
    @Autowired
    JdbcClient jdbc;

    @AfterEach
    void clear() {
        TestAuthentication.clear();
    }

    private record Setup(Day7TestFixtures.Tenant tenant, Day8TestFixtures.Student student, UUID officer, UUID applicationId) {
    }

    private Setup setUp(String prefix) {
        var tenant = fixtures.provisionTenant(prefix);
        UUID dept = fixtures.provisionDepartment(tenant, prefix + "D");
        UUID programId = fixtures.provisionProgram(tenant, dept, prefix + "P");
        UUID curriculumId = fixtures.provisionCurriculum(tenant, programId, "v1", java.time.LocalDate.of(2025, 1, 1), null);
        var student = day8Fixtures.provisionStudentWithActiveProgram(tenant, programId, curriculumId, prefix + "S");
        UUID officer = day12Fixtures.provisionPlacementOfficer(tenant, prefix + "PO");
        TestAuthentication.authenticateAs(officer);
        UUID jobId = ((JobService.CreateResult.Success) jobService.create(tenant.tenantId(), "Analyst", "Example Co", null)).jobId();
        TestAuthentication.clear();
        TestAuthentication.authenticateAs(student.userId());
        UUID applicationId = ((ApplicationService.ApplyResult.Success) applicationService.apply(jobId, student.studentId())).applicationId();
        TestAuthentication.clear();
        return new Setup(tenant, student, officer, applicationId);
    }

    private Map<String, Object> stored(UUID applicationId) {
        return jdbc.sql("SELECT status, decision_reason, decided_at FROM career.applications WHERE id = :id").param("id", applicationId).query().singleRow();
    }

    private Map<String, Object> announced(UUID applicationId) {
        return jdbc.sql("SELECT event_type, payload_json::text AS payload FROM governance.outbox_events WHERE payload_json ->> 'applicationId' = :id AND event_type = 'ApplicationDecided' ORDER BY occurred_at DESC LIMIT 1")
            .param("id", applicationId.toString()).query().singleRow();
    }

    @Test
    void aRejectionNeedsAReasonAndNothingChangesWithout() {
        var s = setUp("AD1");
        TestAuthentication.authenticateAs(s.officer);

        assertThat(applicationService.reject(s.applicationId, null)).isInstanceOf(ApplicationService.TransitionResult.ReasonRequired.class);
        assertThat(applicationService.reject(s.applicationId, "   ")).isInstanceOf(ApplicationService.TransitionResult.ReasonRequired.class);

        assertThat(stored(s.applicationId).get("status")).isEqualTo("SUBMITTED");
        assertThat(stored(s.applicationId).get("decision_reason")).isNull();
    }

    @Test
    void aRejectionIsStoredWithItsReasonAndWhenAndAnnouncedToTheStudent() {
        var s = setUp("AD2");
        TestAuthentication.authenticateAs(s.officer);

        assertThat(applicationService.reject(s.applicationId, "  Minimum attendance not met  ")).isInstanceOf(ApplicationService.TransitionResult.Success.class);

        var row = stored(s.applicationId);
        assertThat(row.get("status")).isEqualTo("REJECTED");
        assertThat(row.get("decision_reason")).isEqualTo("Minimum attendance not met");
        assertThat(row.get("decided_at")).isNotNull();
        String payload = (String) announced(s.applicationId).get("payload");
        assertThat(payload).contains("\"decision\": \"REJECTED\"").contains("Minimum attendance not met").contains(s.student.studentId().toString())
            .contains("Analyst").contains("Example Co");
    }

    @Test
    void aShortlistAndASelectionNeedNoReasonButKeepOneIfGiven() {
        var s = setUp("AD3");
        TestAuthentication.authenticateAs(s.officer);

        assertThat(applicationService.shortlist(s.applicationId)).isInstanceOf(ApplicationService.TransitionResult.Success.class);
        assertThat(stored(s.applicationId).get("decision_reason")).isNull();
        assertThat(applicationService.select(s.applicationId, "Strong interview")).isInstanceOf(ApplicationService.TransitionResult.Success.class);

        assertThat(stored(s.applicationId).get("status")).isEqualTo("SELECTED");
        assertThat(stored(s.applicationId).get("decision_reason")).isEqualTo("Strong interview");
    }

    @Test
    void aReasonLongerThanTheLimitIsRefused() {
        var s = setUp("AD4");
        TestAuthentication.authenticateAs(s.officer);

        assertThat(applicationService.reject(s.applicationId, "x".repeat(ApplicationService.MAX_REASON_LENGTH + 1))).isInstanceOf(ApplicationService.TransitionResult.ReasonTooLong.class);
        assertThat(applicationService.reject(s.applicationId, "x".repeat(ApplicationService.MAX_REASON_LENGTH))).isInstanceOf(ApplicationService.TransitionResult.Success.class);
    }

    @Test
    void aDecidedApplicationCannotBeDecidedAgainOrChangeItsReason() {
        var s = setUp("AD5");
        TestAuthentication.authenticateAs(s.officer);
        applicationService.reject(s.applicationId, "Not a fit for this role");

        assertThat(applicationService.select(s.applicationId, "Changed our minds")).isInstanceOf(ApplicationService.TransitionResult.InvalidTransition.class);
        assertThat(stored(s.applicationId).get("decision_reason")).isEqualTo("Not a fit for this role");
    }

    @Test
    void aStudentCannotDecideTheirOwnApplicationAndAnotherCollegesOfficerCannotEither() {
        var s = setUp("AD6");
        var other = fixtures.provisionTenant("AD6B");
        UUID otherOfficer = day12Fixtures.provisionPlacementOfficer(other, "AD6BPO");

        TestAuthentication.authenticateAs(s.student.userId());
        assertThatThrownBy(() -> applicationService.select(s.applicationId, "me")).isInstanceOf(AccessDeniedException.class);
        TestAuthentication.clear();
        TestAuthentication.authenticateAs(otherOfficer);
        assertThatThrownBy(() -> applicationService.reject(s.applicationId, "someone else's")).isInstanceOf(AccessDeniedException.class);

        assertThat(stored(s.applicationId).get("status")).isEqualTo("SUBMITTED");
    }

    @Test
    void theDatabaseItselfRefusesARejectionWithoutAReason() {
        var s = setUp("AD7");

        assertThatThrownBy(() -> jdbc.sql("UPDATE career.applications SET status = 'REJECTED', decided_at = now() WHERE id = :id").param("id", s.applicationId).update())
            .hasMessageContaining("ck_applications_rejection_has_reason");
    }
}
