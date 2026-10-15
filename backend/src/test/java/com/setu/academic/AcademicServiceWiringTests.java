package com.setu.academic;

import com.setu.institution.application.CollegeDirectoryService;
import com.setu.shared.authorization.AuthorizationContext;
import com.setu.shared.authorization.AuthorizationEngine;
import com.setu.shared.identity.PersonDirectory;
import com.setu.shared.security.Jwks;
import com.setu.shared.security.JwksKeySource;
import com.setu.shared.security.LoginRevocations;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The Academic service on its own: only its modules and the shared kernel are in the context (nothing from Identity or any other
 * service), it authorizes from its read model through the shared engine, and it authenticates tokens against a published key set and the
 * revocation list. Tokens here are signed by a test key whose JWKS the service is given, exactly as it would fetch Identity's.
 */
@SpringBootTest(classes = AcademicServiceApplication.class, properties = {
    "setu.service=academic", "setu.auth.mode=remote", "app.academic.consumer.enabled=false"})   // Flyway stays on here only so the test never depends on another test having migrated the schema first
@AutoConfigureMockMvc
@Import(AcademicServiceWiringTests.IdentityKeys.class)
class AcademicServiceWiringTests {

    private static final KeyPair IDENTITY_KEY = newPair();
    private static final KeyPair FOREIGN_KEY = newPair();

    /** What the service would fetch from Identity's /.well-known/jwks.json. */
    @TestConfiguration
    static class IdentityKeys {
        @Bean
        @Primary
        JwksKeySource testJwksKeySource() {
            String jwks = Jwks.toJson(Map.of("test-key", IDENTITY_KEY.getPublic()));
            return new JwksKeySource(() -> jwks, Clock.systemUTC(), Duration.ofMinutes(10), Duration.ofSeconds(30));
        }
    }

    private static final List<String> OTHER_SERVICES = List.of("identity", "assessment", "attendance", "learning", "record", "finance",
        "career", "risk", "aiexplain", "notification", "analytics");

    @Autowired
    ApplicationContext context;
    @Autowired
    MockMvc mockMvc;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    CollegeDirectoryService collegeDirectoryService;
    @Autowired
    LoginRevocations revocations;

    private UUID tenant;
    private UUID admin;
    private UUID outsider;
    private UUID adminGrant;

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
    void setUp() {
        tenant = collegeDirectoryService.provisionCollege("W" + UUID.randomUUID().toString().substring(0, 8), "Wiring College", "Asia/Kolkata");
        admin = UUID.randomUUID();
        outsider = UUID.randomUUID();
        adminGrant = UUID.randomUUID();
        jdbc.sql("INSERT INTO institution.rm_role_grants (user_role_id, user_id, tenant_id, role_code, starts_at) VALUES (:id, :user, :tenant, 'COLLEGE_ADMIN', now() - interval '1 minute')")
            .param("id", adminGrant).param("user", admin).param("tenant", tenant).update();
    }

    @AfterEach
    void cleanUp() {
        jdbc.sql("DELETE FROM institution.rm_role_grants WHERE user_role_id = :id").param("id", adminGrant).update();
    }

    private String token(UUID user, UUID family, KeyPair signer, String kid) {
        var builder = Jwts.builder().header().keyId(kid).and()
            .subject(user.toString()).issuer("setu").audience().add("setu-api").and()
            .issuedAt(new Date()).expiration(Date.from(Instant.now().plusSeconds(600))).id(UUID.randomUUID().toString());
        if (family != null) {
            builder.claim("fam", family.toString());
        }
        return builder.signWith(signer.getPrivate(), Jwts.SIG.ES256).compact();
    }

    private int viewCollegeAs(String bearer) throws Exception {
        var request = get("/institution/colleges/" + tenant);
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        return mockMvc.perform(request).andReturn().getResponse().getStatus();
    }

    @Test
    void onlyAcademicsModulesAndTheKernelAreInTheContext() {
        List<String> foreign = Arrays.stream(context.getBeanDefinitionNames())
            .map(context::getType)
            .filter(type -> type != null && type.getName().startsWith("com.setu."))
            .map(Class::getName)
            .filter(name -> OTHER_SERVICES.stream().anyMatch(service -> name.startsWith("com.setu." + service + ".")))
            .toList();
        assertThat(foreign).isEmpty();
        assertThat(Arrays.stream(context.getBeanDefinitionNames()).map(context::getType).filter(t -> t != null).map(Class::getName))
            .noneMatch(n -> n.startsWith("com.setu.shared.admin.") || n.startsWith("com.setu.shared.seed."));
    }

    @Test
    void authorizationAndPeopleComeFromTheReadModelNotFromIdentity() {
        assertThat(context.getBean(AuthorizationContext.class)).isExactlyInstanceOf(AuthorizationEngine.class);
        assertThat(context.getBean(PersonDirectory.class)).isExactlyInstanceOf(com.setu.academic.readmodel.ReadModelPersonDirectory.class);
    }

    @Test
    void aRequestWithoutAValidTokenIsRefused() throws Exception {
        assertThat(viewCollegeAs(null)).isEqualTo(401);
        assertThat(viewCollegeAs("garbage")).isEqualTo(401);
        assertThat(viewCollegeAs(token(admin, null, FOREIGN_KEY, "test-key"))).as("signed by another key").isEqualTo(401);
        assertThat(viewCollegeAs(token(admin, null, IDENTITY_KEY, "unknown-key"))).as("unknown key id").isEqualTo(401);
    }

    @Test
    void aRoleInTheReadModelAuthorizesTheRequest() throws Exception {
        assertThat(viewCollegeAs(token(admin, null, IDENTITY_KEY, "test-key"))).isEqualTo(200);
    }

    @Test
    void aValidTokenWithoutARoleInThatTenantIsForbidden() throws Exception {
        assertThat(viewCollegeAs(token(outsider, null, IDENTITY_KEY, "test-key"))).isEqualTo(403);
    }

    @Test
    void aRoleEndedInTheReadModelStopsWorkingAtOnce() throws Exception {
        String bearer = token(admin, null, IDENTITY_KEY, "test-key");
        assertThat(viewCollegeAs(bearer)).isEqualTo(200);

        jdbc.sql("UPDATE institution.rm_role_grants SET ends_at = now() - interval '1 second' WHERE user_role_id = :id").param("id", adminGrant).update();

        assertThat(viewCollegeAs(bearer)).isEqualTo(403);
    }

    @Test
    void aRevokedLoginIsRefusedEvenWithAGoodSignatureAndRole() throws Exception {
        UUID family = UUID.randomUUID();
        String bearer = token(admin, family, IDENTITY_KEY, "test-key");
        assertThat(viewCollegeAs(bearer)).isEqualTo(200);

        revocations.revokeLogin(family);

        assertThat(viewCollegeAs(bearer)).isEqualTo(401);
    }

    @Test
    void healthProbesStayPublicAndIdentityEndpointsDoNotExistHere() throws Exception {
        assertThat(mockMvc.perform(get("/actuator/health")).andReturn().getResponse().getStatus()).isNotEqualTo(401);
        mockMvc.perform(get("/.well-known/jwks.json")).andExpect(status().isUnauthorized());   // Identity's endpoint, not served here
    }
}
