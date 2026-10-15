package com.setu.academic;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.setu.academic.application.IdentityEventProjector;
import com.setu.academic.application.PoisonEventException;
import com.setu.academic.readmodel.ReadModelPersonDirectory;
import com.setu.academic.readmodel.ReadModelRoleGrantSource;
import com.setu.identity.TestAuthentication;
import com.setu.identity.application.PersonService;
import com.setu.identity.application.RoleAssignmentService;
import com.setu.identity.application.UserDirectoryService;
import com.setu.identity.application.UserRegistrationService;
import com.setu.identity.domain.RoleCode;
import com.setu.identity.infrastructure.authorization.IdentityRoleGrantSource;
import com.setu.institution.application.CollegeDirectoryService;
import com.setu.shared.authorization.AuthorizationEngine;
import com.setu.shared.authorization.GuardianDelegationChecker;
import com.setu.shared.authorization.ResourceRef;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The Academic service's read model must say exactly what Identity's tables say. Role and person changes are made through Identity's real
 * services, the events they write to the outbox are fed to the projector the way the Kafka consumer does, and then the two sources are
 * compared: the same role grants, the same permission matrix, the same authorization decisions, the same people. If an event ever stops
 * carrying something the read model needs, this fails.
 */
@SpringBootTest
@Transactional
class ReadModelParityTests {

    private static final List<String> PERMISSIONS = List.of("MANAGE_COURSES", "VIEW_TENANT_DATA", "MANAGE_STUDENTS", "MARK_ATTENDANCE", "VIEW_GRADES");

    @Autowired
    JdbcClient jdbc;
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    EntityManager entityManager;
    @Autowired
    RoleAssignmentService roleAssignmentService;
    @Autowired
    UserDirectoryService userDirectoryService;
    @Autowired
    UserRegistrationService userRegistrationService;
    @Autowired
    CollegeDirectoryService collegeDirectoryService;
    @Autowired
    PersonService personService;
    @Autowired
    IdentityRoleGrantSource identityRoles;

    @AfterEach
    void clearSecurityContext() {
        TestAuthentication.clear();
    }

    private IdentityEventProjector projector() {
        return new IdentityEventProjector(jdbc, objectMapper);
    }

    private UUID newUser(String prefix) {
        return ((UserRegistrationService.Outcome.Created) userRegistrationService.register(prefix + "-" + UUID.randomUUID() + "@example.com")).userId();
    }

    private UUID newTenant() {
        return collegeDirectoryService.provisionCollege("P" + UUID.randomUUID().toString().substring(0, 8), "Parity College", "Asia/Kolkata");
    }

    /** Feeds every outbox event written for this aggregate to the projector, oldest first, as the Kafka consumer would. */
    private void replay(UUID aggregateId) {
        entityManager.flush();
        List<Map<String, Object>> rows = jdbc.sql("SELECT id, event_type, payload_json::text AS payload FROM governance.outbox_events "
                + "WHERE aggregate_id = :id ORDER BY occurred_at, id")
            .param("id", aggregateId).query().listOfRows();
        for (Map<String, Object> row : rows) {
            projector().apply((String) row.get("event_type"), row.get("id").toString(), (String) row.get("payload"));
        }
    }

    private AuthorizationEngine engine(com.setu.shared.authorization.RoleGrantSource source) {
        ObjectProvider<GuardianDelegationChecker> none = new StaticListableBeanFactory().getBeanProvider(GuardianDelegationChecker.class);
        return new AuthorizationEngine(source, none, 5);
    }

    private void assertSameDecisions(UUID user, UUID... tenants) {
        var readModel = new ReadModelRoleGrantSource(jdbc);
        assertThat(new TreeSet<>(readModel.activeGrants(user, Instant.now()).stream().map(Object::toString).toList()))
            .isEqualTo(new TreeSet<>(identityRoles.activeGrants(user, Instant.now()).stream().map(Object::toString).toList()));
        AuthorizationEngine fromIdentity = engine(identityRoles);
        AuthorizationEngine fromReadModel = engine(readModel);
        for (UUID tenant : tenants) {
            for (String permission : PERMISSIONS) {
                ResourceRef resource = ResourceRef.of("thing", UUID.randomUUID(), tenant);
                assertThat(fromReadModel.canAsUser(user, permission, resource))
                    .as("%s in tenant %s", permission, tenant)
                    .isEqualTo(fromIdentity.canAsUser(user, permission, resource));
            }
        }
    }

    @Test
    void thePermissionMatrixIsIdenticalToIdentitys() {
        Set<String> identity = new TreeSet<>(jdbc.sql("SELECT r.code || ':' || rp.permission_code FROM identity.role_permissions rp "
            + "JOIN identity.roles r ON r.id = rp.role_id").query(String.class).list());
        Set<String> copy = new TreeSet<>(jdbc.sql("SELECT role_code || ':' || permission_code FROM institution.rm_role_permissions")
            .query(String.class).list());
        assertThat(identity).isNotEmpty();
        assertThat(copy).isEqualTo(identity);
    }

    @Test
    void anAssignedRoleGivesTheSameDecisionsAsIdentity() {
        UUID tenant = newTenant();
        UUID otherTenant = newTenant();
        UUID admin = newUser("rm-admin");
        UUID faculty = newUser("rm-faculty");
        UUID adminRole = userDirectoryService.grantBootstrapRole(admin, RoleCode.COLLEGE_ADMIN, tenant);
        UUID facultyRole = userDirectoryService.grantBootstrapRole(faculty, RoleCode.FACULTY, tenant);
        UUID superAdminRole = userDirectoryService.grantBootstrapRole(newUser("rm-super"), RoleCode.SUPER_ADMIN, null);

        replay(adminRole);
        replay(facultyRole);
        replay(superAdminRole);

        assertSameDecisions(admin, tenant, otherTenant);
        assertSameDecisions(faculty, tenant, otherTenant);
        assertThat(engine(new ReadModelRoleGrantSource(jdbc)).canAsUser(admin, "MANAGE_COURSES", ResourceRef.of("course", UUID.randomUUID(), tenant)).isAllowed()).isTrue();
        assertThat(engine(new ReadModelRoleGrantSource(jdbc)).canAsUser(admin, "MANAGE_COURSES", ResourceRef.of("course", UUID.randomUUID(), otherTenant)).isAllowed()).isFalse();
    }

    @Test
    void aRevokedRoleStopsWorkingInTheReadModelToo() {
        UUID tenant = newTenant();
        UUID admin = newUser("rm-revoker");
        userDirectoryService.grantBootstrapRole(admin, RoleCode.COLLEGE_ADMIN, tenant);
        UUID student = newUser("rm-student");
        TestAuthentication.authenticateAs(admin, Instant.now());
        UUID assignment = roleAssignmentService.assignRole(student, RoleCode.STUDENT, tenant);
        replay(assignment);
        assertSameDecisions(student, tenant);
        assertThat(new ReadModelRoleGrantSource(jdbc).activeGrants(student, Instant.now())).hasSize(1);

        roleAssignmentService.revokeRole(assignment);
        replay(assignment);   // the assigned event is delivered again: a duplicate, ignored; the revoked one is new

        assertThat(new ReadModelRoleGrantSource(jdbc).activeGrants(student, Instant.now())).isEmpty();
        assertSameDecisions(student, tenant);
    }

    @Test
    void aRevocationDeliveredBeforeItsAssignmentStillEndsTheGrant() {
        UUID tenant = newTenant();
        UUID admin = newUser("rm-order-admin");
        userDirectoryService.grantBootstrapRole(admin, RoleCode.COLLEGE_ADMIN, tenant);
        UUID student = newUser("rm-order-student");
        TestAuthentication.authenticateAs(admin, Instant.now());
        UUID assignment = roleAssignmentService.assignRole(student, RoleCode.STUDENT, tenant);
        roleAssignmentService.revokeRole(assignment);
        entityManager.flush();
        var events = jdbc.sql("SELECT id, event_type, payload_json::text AS payload FROM governance.outbox_events WHERE aggregate_id = :id ORDER BY occurred_at, id")
            .param("id", assignment).query().listOfRows();
        assertThat(events).extracting(r -> r.get("event_type")).containsExactly("UserRoleAssigned", "UserRoleRevoked");

        for (int i = 1; i >= 0; i--) {   // revoked first, then assigned
            projector().apply((String) events.get(i).get("event_type"), events.get(i).get("id").toString(), (String) events.get(i).get("payload"));
        }

        assertThat(new ReadModelRoleGrantSource(jdbc).activeGrants(student, Instant.now())).isEmpty();
    }

    @Test
    void aRedeliveredEventIsAppliedOnlyOnce() {
        UUID user = newUser("rm-dup");
        UUID role = userDirectoryService.grantBootstrapRole(user, RoleCode.STUDENT, newTenant());
        entityManager.flush();
        var row = jdbc.sql("SELECT id, payload_json::text AS payload FROM governance.outbox_events WHERE aggregate_id = :id AND event_type = 'UserRoleAssigned'")
            .param("id", role).query().singleRow();

        assertThat(projector().apply("UserRoleAssigned", row.get("id").toString(), (String) row.get("payload"))).isEqualTo(IdentityEventProjector.Outcome.APPLIED);
        assertThat(projector().apply("UserRoleAssigned", row.get("id").toString(), (String) row.get("payload"))).isEqualTo(IdentityEventProjector.Outcome.DUPLICATE);
        assertThat(jdbc.sql("SELECT count(*) FROM institution.rm_role_grants WHERE user_role_id = :id").param("id", role).query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void peopleAreCopiedAndKeptCurrent() {
        UUID user = newUser("rm-person");
        UUID person = personService.createPerson(user, "Asha Verma", "Asha", LocalDate.of(2004, 5, 1), "F", "asha@example.com", "+911234567890", null);
        replay(person);
        var readModel = new ReadModelPersonDirectory(jdbc);
        assertThat(readModel.findById(person)).isEqualTo(personService.findById(person));
        assertThat(readModel.findByUserId(user)).isEqualTo(personService.findByUserId(user));

        personService.update(person, "Asha K. Verma", "Asha", "asha.v@example.com", "+911234567891");
        replay(person);

        assertThat(readModel.findById(person)).isEqualTo(personService.findById(person));
        assertThat(readModel.findById(person).orElseThrow().legalName()).isEqualTo("Asha K. Verma");
    }

    @Test
    void theCopyHoldsNoMoreThanNameAndContact() {
        UUID person = personService.createPerson(newUser("rm-pii"), "Ravi Rao", null, LocalDate.of(1990, 1, 1), "M", "ravi@example.com", null, "{\"city\":\"Pune\"}");
        entityManager.flush();
        String payload = jdbc.sql("SELECT payload_json::text FROM governance.outbox_events WHERE aggregate_id = :id AND event_type = 'PersonRegistered'")
            .param("id", person).query(String.class).single();
        assertThat(payload).doesNotContain("1990").doesNotContain("Pune").doesNotContain("dob").doesNotContain("gender").doesNotContain("address");
    }

    @Test
    void theReadOnlyDirectoryRefusesToCreateOrChangePeople() {
        var readModel = new ReadModelPersonDirectory(jdbc);
        assertThatThrownBy(() -> readModel.createPerson(UUID.randomUUID(), "X", null, null, null, null, null, null))
            .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> readModel.update(UUID.randomUUID(), "X", null, null, null)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void eventsItHasNoUseForAreIgnoredAndMalformedOnesArePoison() {
        assertThat(projector().apply("GradePublished", UUID.randomUUID().toString(), "{}")).isEqualTo(IdentityEventProjector.Outcome.IGNORED);
        assertThatThrownBy(() -> projector().apply("UserRoleAssigned", UUID.randomUUID().toString(), "not json")).isInstanceOf(PoisonEventException.class);
        assertThatThrownBy(() -> projector().apply("UserRoleAssigned", UUID.randomUUID().toString(), "{\"occurredAt\":1,\"userRoleId\":\"" + UUID.randomUUID() + "\",\"userId\":\"" + UUID.randomUUID() + "\"}"))
            .isInstanceOf(PoisonEventException.class);   // no roleCode
        assertThatThrownBy(() -> projector().apply("UserRoleAssigned", "not-a-uuid", "{}")).isInstanceOf(PoisonEventException.class);
    }
}
