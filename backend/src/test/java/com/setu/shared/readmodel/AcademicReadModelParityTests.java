package com.setu.shared.readmodel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.setu.directory.application.FacultyProfileService;
import com.setu.directory.domain.FacultyStatus;
import com.setu.enrollment.application.EnrollmentService;
import com.setu.enrollment.application.FacultyAssignmentService;
import com.setu.guardianaccess.application.GuardianAccessService;
import com.setu.identity.TestAuthentication;
import com.setu.identity.application.RoleAssignmentService;
import com.setu.identity.application.UserDirectoryService;
import com.setu.identity.application.UserRegistrationService;
import com.setu.identity.domain.RoleCode;
import com.setu.catalog.application.MeetingPatternService;
import com.setu.institution.application.CollegeService;
import com.setu.shared.academic.AcademicDirectory;
import com.setu.shared.authorization.GuardianDelegationChecker;
import com.setu.shared.authorization.RoleGrant;
import com.setu.shared.enrollment.CourseSectionDirectory;
import com.setu.testsupport.Day10TestFixtures;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The read model an extracted service keeps must say exactly what the monolith's Academic says. A whole college is built through Academic's
 * real services (so every event is the one production writes), the events are read back from the outbox and fed to the projectors the way
 * the Kafka consumer does, and then every question {@link AcademicDirectory}, {@link CourseSectionDirectory} and the guardian check can
 * be asked is put to both. The same is done after each kind of change, and again with the whole history delivered in reverse order, because
 * the projections must converge whatever order a replay or a redelivery brings the events in.
 */
@SpringBootTest
class AcademicReadModelParityTests {

    private static final List<String> SCHEMAS = List.of("learning", "assessment", "attendance");

    @Autowired
    JdbcClient jdbc;
    @Autowired
    com.setu.enrollment.application.FinalGradeEventProcessor processor;
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    Day7TestFixtures day7;
    @Autowired
    Day8TestFixtures day8;
    @Autowired
    Day9TestFixtures day9;
    @Autowired
    Day10TestFixtures day10;
    @Autowired
    AcademicDirectory monolithDirectory;
    @Autowired
    CourseSectionDirectory monolithSections;
    @Autowired
    GuardianDelegationChecker monolithGuardians;
    @Autowired
    EnrollmentService enrollmentService;
    @Autowired
    FacultyAssignmentService facultyAssignmentService;
    @Autowired
    FacultyProfileService facultyProfileService;
    @Autowired
    CollegeService collegeService;
    @Autowired
    MeetingPatternService meetingPatternService;
    @Autowired
    GuardianAccessService guardianAccessService;
    @Autowired
    UserRegistrationService userRegistrationService;
    @Autowired
    UserDirectoryService userDirectoryService;
    @Autowired
    RoleAssignmentService roleAssignmentService;

    @AfterEach
    void clearSecurityContext() {
        TestAuthentication.clear();
    }

    private record College(Day7TestFixtures.Tenant tenant, UUID section, UUID meeting, Day9TestFixtures.Faculty faculty,
                           Day8TestFixtures.Student student, UUID enrollment, Day10TestFixtures.Guardian guardian, UUID grant, UUID bootstrapUser, UUID assignedUser, UUID assignedRole, Instant startedAt) {
    }

    private College buildCollege(String prefix) {
        Instant startedAt = Instant.now().minusSeconds(1);
        var tenant = day7.provisionTenant(prefix);
        UUID dept = day7.provisionDepartment(tenant, prefix + "D");
        UUID program = day7.provisionProgram(tenant, dept, prefix + "P");
        UUID curriculum = day7.provisionCurriculum(tenant, program, "v1", LocalDate.of(2025, 1, 1), null);
        UUID course = day7.provisionCourse(tenant, dept, prefix + "C");
        UUID period = day8.provisionOpenPeriod(tenant, prefix + "PR");
        UUID building = day7.provisionBuilding(tenant, prefix + "B");
        UUID room = day7.provisionRoom(tenant, building, "101", 40);
        UUID section = day8.provisionOpenSection(tenant, course, period, "S1", 30, room);
        UUID meeting = day9.provisionMeetingPattern(tenant, section, room);
        var faculty = day9.provisionFacultyAssignedToSection(tenant, section, prefix + "F");
        var student = day8.provisionStudentWithActiveProgram(tenant, program, curriculum, prefix + "S");
        UUID enrollment = day9.enrollStudent(student, section);
        var guardian = day10.provisionGuardian(tenant, prefix + "G");
        TestAuthentication.authenticateAs(tenant.adminUserId(), Instant.now());
        UUID grant = ((GuardianAccessService.GrantResult.Success) guardianAccessService.grantAccess(student.studentId(), guardian.guardianId(), null,
            Set.of("VIEW_ATTENDANCE", "VIEW_GRADES"))).grantId();
        // Roles reach a read model only through the events the role services write. The shared fixtures grant theirs straight into the table, so the
        // role comparison below uses accounts given a role the way production gives one: at bootstrap, and by an administrator.
        UUID bootstrapUser = ((com.setu.identity.application.UserRegistrationService.Outcome.Created)
            userRegistrationService.register(prefix.toLowerCase() + "-boot-" + UUID.randomUUID() + "@example.com")).userId();
        userDirectoryService.grantBootstrapRole(bootstrapUser, RoleCode.FACULTY, tenant.tenantId());
        UUID assignedUser = ((com.setu.identity.application.UserRegistrationService.Outcome.Created)
            userRegistrationService.register(prefix.toLowerCase() + "-asg-" + UUID.randomUUID() + "@example.com")).userId();
        UUID assignedRole = roleAssignmentService.assignRole(assignedUser, RoleCode.STUDENT, tenant.tenantId());
        TestAuthentication.clear();
        return new College(tenant, section, meeting, faculty, student, enrollment, guardian, grant, bootstrapUser, assignedUser, assignedRole, startedAt);
    }

    private record Outbox(UUID id, String type, String payload) {
    }

    private List<Outbox> history(College c) {
        return jdbc.sql("SELECT id, event_type, payload_json::text AS payload FROM governance.outbox_events "
                + "WHERE tenant_id = :t AND occurred_at >= :since ORDER BY occurred_at, id")
            .param("t", c.tenant.tenantId()).param("since", java.sql.Timestamp.from(c.startedAt))
            .query((rs, i) -> new Outbox(rs.getObject("id", UUID.class), rs.getString("event_type"), rs.getString("payload"))).list();
    }

    private void deliver(String schema, List<Outbox> events) {
        var identity = new IdentityEventProjector(jdbc, objectMapper, schema, false);
        var academic = new AcademicEventProjector(jdbc, objectMapper, schema);
        for (Outbox e : events) {
            if (identity.handles(e.type())) {
                identity.apply(e.type(), e.id().toString(), e.payload());
            } else if (academic.handles(e.type())) {
                academic.apply(e.type(), e.id().toString(), e.payload());
            }
        }
    }

    private void assertParity(String schema, College c) {
        var directory = new ReadModelAcademicDirectory(jdbc, schema);
        UUID tenant = c.tenant.tenantId();
        UUID outsider = UUID.randomUUID();

        assertThat(directory.studentIdOfUser(tenant, c.student.userId())).isEqualTo(monolithDirectory.studentIdOfUser(tenant, c.student.userId()));
        assertThat(directory.studentIdOfUser(tenant, outsider)).isEmpty();
        assertThat(directory.studentOwner(c.student.studentId())).isEqualTo(monolithDirectory.studentOwner(c.student.studentId()));
        assertThat(directory.facultyIdOfUser(tenant, c.faculty.userId())).isEqualTo(monolithDirectory.facultyIdOfUser(tenant, c.faculty.userId()));
        assertThat(directory.facultyTenant(c.faculty.facultyId())).isEqualTo(monolithDirectory.facultyTenant(c.faculty.facultyId()));
        assertThat(directory.facultyStatus(c.faculty.facultyId())).isEqualTo(monolithDirectory.facultyStatus(c.faculty.facultyId()));
        assertThat(directory.isActivelyAssigned(c.section, c.faculty.facultyId())).isEqualTo(monolithDirectory.isActivelyAssigned(c.section, c.faculty.facultyId()));
        assertThat(directory.isActivelyAssigned(c.section, UUID.randomUUID())).isFalse();
        assertThat(directory.activeEnrollmentId(c.section, c.student.studentId())).isEqualTo(monolithDirectory.activeEnrollmentId(c.section, c.student.studentId()));
        assertThat(directory.gradableEnrollmentId(c.section, c.student.studentId())).isEqualTo(monolithDirectory.gradableEnrollmentId(c.section, c.student.studentId()));
        assertThat(directory.enrollment(c.enrollment)).isEqualTo(monolithDirectory.enrollment(c.enrollment));
        assertThat(directory.activeEnrollmentIds(c.section)).containsExactlyInAnyOrderElementsOf(monolithDirectory.activeEnrollmentIds(c.section));
        assertThat(directory.meeting(c.meeting)).isEqualTo(monolithDirectory.meeting(c.meeting));
        assertThat(directory.timeZoneOf(tenant)).isEqualTo(monolithDirectory.timeZoneOf(tenant));
        assertThat(new ReadModelCourseSectionDirectory(jdbc, schema).findTenantId(c.section)).isEqualTo(monolithSections.findTenantId(c.section));

        var roles = new ReadModelRoleGrantSource(jdbc, schema);
        for (UUID user : List.of(c.bootstrapUser, c.assignedUser)) {
            assertThat(new TreeSet<>(roles.activeGrants(user, Instant.now()).stream().map(RoleGrant::toString).toList()))
                .as("roles of %s", user).isEqualTo(new TreeSet<>(identityGrants(user)));
        }

        var guardians = new ReadModelGuardianDelegationChecker(jdbc, schema);
        for (String permission : List.of("VIEW_ATTENDANCE", "VIEW_GRADES", "VIEW_FINANCE")) {
            assertThat(guardians.hasActiveDelegatedAccess(c.guardian.guardianUserId(), c.student.studentId(), tenant, permission, Instant.now()))
                .as("guardian %s", permission)
                .isEqualTo(monolithGuardians.hasActiveDelegatedAccess(c.guardian.guardianUserId(), c.student.studentId(), tenant, permission, Instant.now()));
        }
        assertThat(guardians.hasActiveDelegatedAccess(outsider, c.student.studentId(), tenant, "VIEW_ATTENDANCE", Instant.now())).isFalse();
    }

    private List<String> identityGrants(UUID user) {
        return jdbc.sql("SELECT r.code AS code, ur.tenant_id AS tenant FROM identity.user_roles ur JOIN identity.roles r ON r.id = ur.role_id "
                + "WHERE ur.user_id = :u AND ur.starts_at <= now() AND (ur.ends_at IS NULL OR ur.ends_at > now())")
            .param("u", user).query((rs, i) -> new RoleGrant(rs.getString("code"), rs.getObject("tenant", UUID.class)).toString()).list();
    }

    @Test
    void theCopyAnswersLikeAcademicAfterTheCollegeIsBuiltAndAgainAfterEveryKindOfChange() {
        College c = buildCollege("RP1");
        for (String schema : SCHEMAS) {
            deliver(schema, history(c));
            assertParity(schema, c);
        }
        assertThat(monolithDirectory.activeEnrollmentId(c.section, c.student.studentId())).isPresent();   // the scenario is not vacuous

        // an enrollment ends
        TestAuthentication.authenticateAs(c.student.userId());
        enrollmentService.drop(c.enrollment, "test");
        // a faculty assignment ends
        TestAuthentication.authenticateAs(c.tenant.adminUserId(), Instant.now());
        UUID assignment = facultyAssignmentService.listForSection(c.section, c.tenant.tenantId()).get(0).id();
        facultyAssignmentService.end(assignment, LocalDate.now());
        // an administrator takes a role away
        roleAssignmentService.revokeRole(c.assignedRole);
        // a faculty member goes on leave
        facultyProfileService.changeStatus(c.faculty.facultyId(), FacultyStatus.ON_LEAVE);
        // the college moves time zone
        collegeService.update(c.tenant.tenantId(), "Renamed College", "America/New_York", "en");
        // a meeting is removed
        meetingPatternService.remove(c.meeting);
        // the guardian's access is withdrawn
        guardianAccessService.revokeAccess(c.grant, "test");
        TestAuthentication.clear();

        for (String schema : SCHEMAS) {
            deliver(schema, history(c));
            assertParity(schema, c);
            var directory = new ReadModelAcademicDirectory(jdbc, schema);
            assertThat(directory.activeEnrollmentId(c.section, c.student.studentId())).isEmpty();
            assertThat(directory.gradableEnrollmentId(c.section, c.student.studentId())).as("a dropped enrollment cannot be graded").isEmpty();
            assertThat(new ReadModelRoleGrantSource(jdbc, schema).activeGrants(c.assignedUser, Instant.now())).isEmpty();
            assertThat(directory.isActivelyAssigned(c.section, c.faculty.facultyId())).isFalse();
            assertThat(directory.facultyStatus(c.faculty.facultyId())).contains("ON_LEAVE");
            assertThat(directory.timeZoneOf(c.tenant.tenantId()).getId()).isEqualTo("America/New_York");
            assertThat(directory.meeting(c.meeting)).isEmpty();
            assertThat(new ReadModelGuardianDelegationChecker(jdbc, schema)
                .hasActiveDelegatedAccess(c.guardian.guardianUserId(), c.student.studentId(), c.tenant.tenantId(), "VIEW_ATTENDANCE", Instant.now())).isFalse();
        }
    }

    /**
     * Publishing a final grade completes the enrollment. A completed enrollment is no longer active (the student is not in the course any more) but
     * can still be graded (late components, corrections of the final grade), and the copy must say so exactly as Academic does, whichever order
     * the events arrive in.
     */
    @Test
    void aCompletedEnrollmentIsGradableButNotActiveInTheCopyAsInAcademic() {
        College c = buildCollege("RP4");
        UUID eventId = UUID.randomUUID();
        processor.process("FinalGradePublished", eventId.toString(), c.tenant.tenantId().toString(),
            "{\"eventId\":\"" + eventId + "\",\"tenantId\":\"" + c.tenant.tenantId() + "\",\"enrollmentId\":\"" + c.enrollment
                + "\",\"gradeCode\":\"B\",\"publishedBy\":\"" + c.faculty.userId() + "\"}");
        assertThat(monolithDirectory.activeEnrollmentId(c.section, c.student.studentId())).isEmpty();
        assertThat(monolithDirectory.gradableEnrollmentId(c.section, c.student.studentId())).contains(c.enrollment);   // the scenario is not vacuous

        List<Outbox> inOrder = history(c);
        assertThat(inOrder).extracting(Outbox::type).contains("EnrollmentCompleted");
        for (String schema : SCHEMAS) {
            deliver(schema, inOrder);
            assertParity(schema, c);
            assertThat(new ReadModelAcademicDirectory(jdbc, schema).activeEnrollmentId(c.section, c.student.studentId())).isEmpty();
            assertThat(new ReadModelAcademicDirectory(jdbc, schema).gradableEnrollmentId(c.section, c.student.studentId())).contains(c.enrollment);
        }
        // the same history the other way round, into a fresh copy, ends in the same state: the later event wins
        College again = buildCollege("RP5");
        UUID againEventId = UUID.randomUUID();
        processor.process("FinalGradePublished", againEventId.toString(), again.tenant.tenantId().toString(),
            "{\"eventId\":\"" + againEventId + "\",\"tenantId\":\"" + again.tenant.tenantId() + "\",\"enrollmentId\":\"" + again.enrollment
                + "\",\"gradeCode\":\"F\",\"publishedBy\":\"" + again.faculty.userId() + "\"}");
        List<Outbox> backwards = new java.util.ArrayList<>(history(again));
        java.util.Collections.reverse(backwards);
        for (String schema : SCHEMAS) {
            deliver(schema, backwards);
            assertParity(schema, again);
            assertThat(new ReadModelAcademicDirectory(jdbc, schema).gradableEnrollmentId(again.section, again.student.studentId())).contains(again.enrollment);
        }
    }

    @Test
    void theWholeHistoryDeliveredInReverseOrderEndsInTheSameState() {
        College c = buildCollege("RP2");
        TestAuthentication.authenticateAs(c.student.userId());
        enrollmentService.drop(c.enrollment, "test");
        TestAuthentication.authenticateAs(c.tenant.adminUserId(), Instant.now());
        roleAssignmentService.revokeRole(c.assignedRole);
        facultyProfileService.changeStatus(c.faculty.facultyId(), FacultyStatus.ON_LEAVE);
        meetingPatternService.remove(c.meeting);
        guardianAccessService.revokeAccess(c.grant, "test");
        collegeService.update(c.tenant.tenantId(), "Renamed", "Asia/Tokyo", "en");
        TestAuthentication.clear();

        List<Outbox> reversed = new ArrayList<>(history(c));
        Collections.reverse(reversed);
        for (String schema : SCHEMAS) {
            deliver(schema, reversed);
            assertParity(schema, c);
            assertThat(new ReadModelAcademicDirectory(jdbc, schema).timeZoneOf(c.tenant.tenantId()).getId()).isEqualTo("Asia/Tokyo");
        }
    }

    @Test
    void redeliveringTheWholeHistoryChangesNothing() {
        College c = buildCollege("RP3");
        List<Outbox> events = history(c);
        deliver("learning", events);
        Map<String, Long> before = counts("learning");

        var academic = new AcademicEventProjector(jdbc, objectMapper, "learning");
        var identity = new IdentityEventProjector(jdbc, objectMapper, "learning", false);
        for (Outbox e : events) {
            ReadModelProjector p = identity.handles(e.type()) ? identity : academic;
            if (p.handles(e.type())) {
                assertThat(p.apply(e.type(), e.id().toString(), e.payload())).isEqualTo(ReadModelProjector.Outcome.DUPLICATE);
            }
        }

        assertThat(counts("learning")).isEqualTo(before);
        assertParity("learning", c);
    }

    private Map<String, Long> counts(String schema) {
        Map<String, Long> out = new java.util.TreeMap<>();
        for (String table : List.of("rm_role_grants", "rm_students", "rm_faculty", "rm_faculty_assignments", "rm_enrollments", "rm_colleges", "rm_meetings",
            "rm_sections", "rm_guardian_grants", "rm_guardian_grant_permissions", "rm_processed_events")) {
            out.put(table, jdbc.sql("SELECT count(*) FROM " + schema + "." + table).query(Long.class).single());
        }
        return out;
    }

    @Test
    void eventsNoProjectorWantsAreIgnoredAndMalformedOnesArePoison() {
        var academic = new AcademicEventProjector(jdbc, objectMapper, "learning");
        assertThat(academic.apply("GradePublished", UUID.randomUUID().toString(), "{}")).isEqualTo(ReadModelProjector.Outcome.IGNORED);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> academic.apply("StudentEnrolled", UUID.randomUUID().toString(), "not json"))
            .isInstanceOf(PoisonEventException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> academic.apply("StudentEnrolled", UUID.randomUUID().toString(), "{\"occurredAt\":1}"))
            .isInstanceOf(PoisonEventException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> academic.apply("StudentEnrolled", "not-a-uuid", "{}"))
            .isInstanceOf(PoisonEventException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new AcademicEventProjector(jdbc, objectMapper, "learning; DROP TABLE x"))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
