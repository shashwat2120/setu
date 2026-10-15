package com.setu.testsupport;

import com.setu.shared.academic.AcademicDirectory;
import com.setu.shared.authorization.AuthorizationContext;
import com.setu.shared.authorization.AuthorizationEngine;
import com.setu.shared.authorization.GuardianDelegationChecker;
import com.setu.shared.enrollment.CourseSectionDirectory;
import com.setu.shared.readmodel.ReadModelAcademicDirectory;
import com.setu.shared.readmodel.ReadModelCourseSectionDirectory;
import com.setu.shared.readmodel.ReadModelGuardianDelegationChecker;
import com.setu.shared.security.Jwks;
import com.setu.shared.security.JwksKeySource;
import com.setu.shared.security.LoginRevocations;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The checks every extracted service on top of Academic must pass, run against each service's own entry point, schema and a real request:
 * only that service's module and the shared kernel are in the context; authorization, enrollment, assignment and section facts come from its
 * own read model; and a request is decided from that copy, so an assignment or enrollment that ends in the copy stops working on the next
 * request, a revoked login is refused, and a bad token never reaches a controller.
 * <p>
 * Subclasses name the service and give one read endpoint that asks "may this caller see this section's data". Tokens are signed by a test
 * key whose JWKS the service is given, exactly as it would fetch Identity's.
 */
public abstract class ExtractedServiceWiringSupport {

    protected static final KeyPair IDENTITY_KEY = newPair();
    private static final KeyPair FOREIGN_KEY = newPair();

    /** What the service would fetch from Identity's /.well-known/jwks.json. */
    @TestConfiguration
    public static class IdentityKeys {
        @Bean
        @Primary
        JwksKeySource testJwksKeySource() {
            String jwks = Jwks.toJson(Map.of("test-key", IDENTITY_KEY.getPublic()));
            return new JwksKeySource(() -> jwks, Clock.systemUTC(), Duration.ofMinutes(10), Duration.ofSeconds(30));
        }
    }

    @Autowired
    protected ApplicationContext context;
    @Autowired
    protected MockMvc mockMvc;
    @Autowired
    protected JdbcClient jdbc;
    @Autowired
    protected LoginRevocations revocations;

    /** The service's schema, which holds its read model. */
    protected abstract String schema();

    /** The module the service runs, the only {@code com.setu} package besides the kernel allowed in its context. */
    protected abstract String module();

    /** A GET that asks whether the caller may see this section's data. */
    protected abstract MockHttpServletRequestBuilder sectionRead(UUID sectionId, UUID tenantId);

    /** Whether an actively enrolled student may make that read. */
    protected abstract boolean enrolledStudentMayRead();

    protected UUID tenant;
    protected UUID section;
    protected UUID faculty;
    protected UUID assignment;
    protected UUID facultyUser;
    protected UUID student;
    protected UUID studentUser;
    protected UUID enrollment;
    protected UUID stranger;

    private static KeyPair newPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            return generator.generateKeyPair();
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    @BeforeEach
    void seedReadModel() {
        tenant = UUID.randomUUID();
        section = UUID.randomUUID();
        faculty = UUID.randomUUID();
        assignment = UUID.randomUUID();
        facultyUser = UUID.randomUUID();
        student = UUID.randomUUID();
        studentUser = UUID.randomUUID();
        enrollment = UUID.randomUUID();
        stranger = UUID.randomUUID();
        jdbc.sql("INSERT INTO " + schema() + ".rm_sections (section_id, tenant_id, updated_at) VALUES (:s, :t, now())").param("s", section).param("t", tenant).update();
        jdbc.sql("INSERT INTO " + schema() + ".rm_faculty (faculty_id, tenant_id, owner_user_id, status, updated_at) VALUES (:f, :t, :u, 'ACTIVE', now())")
            .param("f", faculty).param("t", tenant).param("u", facultyUser).update();
        jdbc.sql("INSERT INTO " + schema() + ".rm_faculty_assignments (assignment_id, tenant_id, section_id, faculty_id, ends_at, updated_at) VALUES (:a, :t, :s, :f, NULL, now())")
            .param("a", assignment).param("t", tenant).param("s", section).param("f", faculty).update();
        // Roles the way Identity's events would have put them there: the faculty member and the student each hold their role in the college.
        for (var grant : List.of(Map.entry(facultyUser, "FACULTY"), Map.entry(studentUser, "STUDENT"))) {
            jdbc.sql("INSERT INTO " + schema() + ".rm_role_grants (user_role_id, user_id, tenant_id, role_code, starts_at) VALUES (:id, :u, :t, :r, now() - interval '1 minute')")
                .param("id", UUID.randomUUID()).param("u", grant.getKey()).param("t", tenant).param("r", grant.getValue()).update();
        }
        jdbc.sql("INSERT INTO " + schema() + ".rm_students (student_id, tenant_id, owner_user_id, updated_at) VALUES (:s, :t, :u, now())")
            .param("s", student).param("t", tenant).param("u", studentUser).update();
        jdbc.sql("INSERT INTO " + schema() + ".rm_enrollments (enrollment_id, tenant_id, section_id, student_id, active, updated_at) VALUES (:e, :t, :sec, :st, true, now())")
            .param("e", enrollment).param("t", tenant).param("sec", section).param("st", student).update();
    }

    @AfterEach
    void cleanReadModel() {
        for (String table : List.of("rm_sections", "rm_faculty", "rm_faculty_assignments", "rm_students", "rm_enrollments", "rm_role_grants")) {
            jdbc.sql("DELETE FROM " + schema() + "." + table + " WHERE tenant_id = :t").param("t", tenant).update();
        }
    }

    protected String token(UUID user, UUID family, KeyPair signer, String kid) {
        var builder = Jwts.builder().header().keyId(kid).and()
            .subject(user.toString()).issuer("setu").audience().add("setu-api").and()
            .issuedAt(new Date()).expiration(Date.from(Instant.now().plusSeconds(600))).id(UUID.randomUUID().toString());
        if (family != null) {
            builder.claim("fam", family.toString());
        }
        return builder.signWith(signer.getPrivate(), Jwts.SIG.ES256).compact();
    }

    protected int read(String bearer) throws Exception {
        var request = sectionRead(section, tenant);
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        return mockMvc.perform(request).andReturn().getResponse().getStatus();
    }

    @Test
    void onlyThisServicesModuleAndTheKernelAreInTheContext() {
        List<String> types = Arrays.stream(context.getBeanDefinitionNames()).map(context::getType).filter(t -> t != null).map(Class::getName)
            .filter(n -> n.startsWith("com.setu.") && !n.startsWith("com.setu.testsupport.")).toList();
        assertThat(types).as("beans of this service").anyMatch(n -> n.startsWith("com.setu." + module() + "."));
        assertThat(types).as("nothing from another module or service")
            .allMatch(n -> n.startsWith("com.setu." + module() + ".") || n.startsWith("com.setu.shared."), "this module or the kernel");
        assertThat(types).noneMatch(n -> n.startsWith("com.setu.shared.admin.") || n.startsWith("com.setu.shared.seed.") || n.startsWith("com.setu.shared.ai."));
    }

    @Test
    void everythingItAsksAcademicAndIdentityIsAnsweredFromItsOwnReadModel() {
        assertThat(context.getBean(AuthorizationContext.class)).isExactlyInstanceOf(AuthorizationEngine.class);
        assertThat(context.getBean(AcademicDirectory.class)).isExactlyInstanceOf(ReadModelAcademicDirectory.class);
        assertThat(context.getBean(CourseSectionDirectory.class)).isExactlyInstanceOf(ReadModelCourseSectionDirectory.class);
        assertThat(context.getBean(GuardianDelegationChecker.class)).isExactlyInstanceOf(ReadModelGuardianDelegationChecker.class);
    }

    @Test
    void aRequestWithoutAValidTokenNeverReachesTheController() throws Exception {
        assertThat(read(null)).isEqualTo(401);
        assertThat(read("garbage")).isEqualTo(401);
        assertThat(read(token(facultyUser, null, FOREIGN_KEY, "test-key"))).as("signed by another key").isEqualTo(401);
        assertThat(read(token(facultyUser, null, IDENTITY_KEY, "unknown-key"))).as("unknown key id").isEqualTo(401);
    }

    @Test
    void theAssignedFacultyMemberIsDecidedFromTheReadModelAndAnEndedAssignmentStopsWorkingAtOnce() throws Exception {
        String bearer = token(facultyUser, null, IDENTITY_KEY, "test-key");
        assertThat(read(bearer)).isEqualTo(200);

        jdbc.sql("UPDATE " + schema() + ".rm_faculty_assignments SET ends_at = current_date WHERE assignment_id = :a").param("a", assignment).update();

        assertThat(read(bearer)).as("assignment ended in the copy").isEqualTo(403);
    }

    @Test
    void aStrangerWithNoRoleAndNoEnrollmentIsForbidden() throws Exception {
        assertThat(read(token(stranger, null, IDENTITY_KEY, "test-key"))).isEqualTo(403);
    }

    @Test
    void anEnrolledStudentIsDecidedFromTheReadModelAndADroppedEnrollmentStopsWorkingAtOnce() throws Exception {
        String bearer = token(studentUser, null, IDENTITY_KEY, "test-key");
        assertThat(read(bearer)).isEqualTo(enrolledStudentMayRead() ? 200 : 403);

        jdbc.sql("UPDATE " + schema() + ".rm_enrollments SET active = false WHERE enrollment_id = :e").param("e", enrollment).update();

        assertThat(read(bearer)).as("enrollment dropped in the copy").isEqualTo(403);
    }

    @Test
    void aRevokedLoginIsRefusedEvenWithAGoodSignatureAndAnAssignment() throws Exception {
        UUID family = UUID.randomUUID();
        String bearer = token(facultyUser, family, IDENTITY_KEY, "test-key");
        assertThat(read(bearer)).isEqualTo(200);

        revocations.revokeLogin(family);

        assertThat(read(bearer)).isEqualTo(401);
    }
}
