package com.setu.audit;

import com.setu.directory.application.StudentProfileService;
import com.setu.identity.TestAuthentication;
import com.setu.identity.application.UserRegistrationService;
import com.setu.shared.governance.OutboxEventWriter;
import com.setu.shared.identity.PersonDirectory;
import com.setu.testsupport.Day7TestFixtures;
import com.setu.testsupport.Day8TestFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Canonical spec §3 and §8: "authoritative DB write and outbox event commit atomically". The outbox row is written in the caller's own
 * transaction, so if the business change rolls back, the event vanishes with it (no event for something that never happened), and if
 * it commits, both are there. Tested twice: on the writer itself, and on a real business operation, admitting a student.
 */
@SpringBootTest
class OutboxAtomicityTests {

    @Autowired
    PlatformTransactionManager transactionManager;
    @Autowired
    OutboxEventWriter outboxEventWriter;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    Day7TestFixtures fixtures;
    @Autowired
    Day8TestFixtures day8Fixtures;
    @Autowired
    UserRegistrationService registrationService;
    @Autowired
    PersonDirectory personDirectory;
    @Autowired
    StudentProfileService studentProfileService;

    private int outboxRows(String eventType, UUID tenantId) {
        return jdbc.sql("SELECT count(*) FROM governance.outbox_events WHERE event_type = :t AND tenant_id = :tenant")
            .param("t", eventType).param("tenant", tenantId).query(Integer.class).single();
    }

    @Test
    void anEventWrittenInARolledBackTransactionDoesNotExist() {
        UUID tenant = fixtures.provisionTenant("OBX1").tenantId();
        UUID aggregate = UUID.randomUUID();
        var tx = new TransactionTemplate(transactionManager);

        tx.executeWithoutResult(status -> {
            outboxEventWriter.write("test_aggregates", aggregate, "AtomicityProbe", 1, java.util.Map.of("id", aggregate.toString()),
                tenant, UUID.randomUUID(), null);
            status.setRollbackOnly();
        });
        assertThat(outboxRows("AtomicityProbe", tenant)).as("rolled back").isZero();

        tx.executeWithoutResult(status -> outboxEventWriter.write("test_aggregates", aggregate, "AtomicityProbe", 1,
            java.util.Map.of("id", aggregate.toString()), tenant, UUID.randomUUID(), null));
        assertThat(outboxRows("AtomicityProbe", tenant)).as("committed").isEqualTo(1);
    }

    @Test
    void aRealBusinessOperationAndItsEventAreWrittenTogetherOrNotAtAll() {
        // Admitting a student writes the profile and the StudentAdmitted event in one transaction of its own (so that a duplicate
        // admission number can be reported without poisoning the caller's transaction).
        var tenant = fixtures.provisionTenant("OBX2");
        UUID dept = fixtures.provisionDepartment(tenant, "OBX2D");
        UUID programId = fixtures.provisionProgram(tenant, dept, "OBX2P");
        UUID curriculumId = fixtures.provisionCurriculum(tenant, programId, "v1", LocalDate.of(2025, 1, 1), null);
        int eventsBefore = outboxRows("StudentAdmitted", tenant.tenantId());
        int profilesBefore = profiles(tenant.tenantId());

        day8Fixtures.provisionStudentWithActiveProgram(tenant, programId, curriculumId, "OBX2S");

        assertThat(profiles(tenant.tenantId())).as("the student").isEqualTo(profilesBefore + 1);
        assertThat(outboxRows("StudentAdmitted", tenant.tenantId())).as("and the event, together").isEqualTo(eventsBefore + 1);

        // A second person given the SAME admission number: the profile insert fails, so the event written beside it must not survive.
        UUID userId = ((UserRegistrationService.Outcome.Created) registrationService.register("obx-" + UUID.randomUUID() + "@example.com")).userId();
        TestAuthentication.authenticateAs(tenant.adminUserId());
        try {
            UUID personId = personDirectory.createPerson(userId, "Second Person", null, null, null, "obx2-second@example.com", null, null);
            var result = studentProfileService.admitStudent(tenant.tenantId(), personId, "OBX2S-ADM", "OBX2S-ROLL-2", LocalDate.of(2024, 6, 1));
            assertThat(result.getClass().getSimpleName()).isEqualTo("DuplicateAdmissionNo");
        } finally {
            TestAuthentication.clear();
        }

        assertThat(profiles(tenant.tenantId())).as("no second profile").isEqualTo(profilesBefore + 1);
        assertThat(outboxRows("StudentAdmitted", tenant.tenantId())).as("and no event for the admission that did not happen").isEqualTo(eventsBefore + 1);
    }

    private int profiles(UUID tenantId) {
        return jdbc.sql("SELECT count(*) FROM directory.student_profiles WHERE tenant_id = :t").param("t", tenantId).query(Integer.class).single();
    }
}
