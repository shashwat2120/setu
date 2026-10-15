package com.setu.gateway;

import com.setu.shared.security.Jwks;
import com.setu.shared.security.JwksKeySource;
import com.setu.shared.security.LoginRevocations;
import io.jsonwebtoken.Jwts;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.AfterAll;
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
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;
import java.io.IOException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * The gateway as a whole application, end to end up to the socket: the real filter, the real Apache client and the real Redis, with a
 * blocking stub standing in for every service behind it. Tokens are signed by a test key whose JWKS the gateway is given, as it would
 * fetch Identity's.
 */
@SpringBootTest(classes = GatewayApplication.class, properties = {"setu.service=gateway", "setu.auth.mode=remote"})
@AutoConfigureMockMvc
@Import(GatewayWiringTests.IdentityKeys.class)
class GatewayWiringTests {

    private static final KeyPair IDENTITY_KEY = newPair();
    private static ApacheUpstreamTests.Stub stub;

    @TestConfiguration
    static class IdentityKeys {
        @Bean
        @Primary
        JwksKeySource testJwksKeySource() {
            String jwks = Jwks.toJson(Map.of("test-key", IDENTITY_KEY.getPublic()));
            return new JwksKeySource(() -> jwks, Clock.systemUTC(), Duration.ofMinutes(10), Duration.ofSeconds(30));
        }
    }

    @DynamicPropertySource
    static void services(DynamicPropertyRegistry registry) throws IOException {
        stub = new ApacheUpstreamTests.Stub();
        String url = stub.uri().toString();
        registry.add("app.gateway.services.identity.instances", () -> url);
        registry.add("app.gateway.services.academic.instances", () -> url);
        registry.add("app.gateway.services.core.instances", () -> "http://127.0.0.1:1");   // nothing listens: a refused connection
    }

    @AfterAll
    static void stopStub() throws IOException {
        stub.close();
    }

    @Autowired
    ApplicationContext context;
    @Autowired
    MockMvc mockMvc;
    @Autowired
    LoginRevocations revocations;

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
    void resetStub() {
        stub.seen.clear();
        stub.response = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 11\r\n\r\n{\"ok\":true}";
    }

    private String token(UUID user, UUID family) {
        var builder = Jwts.builder().header().keyId("test-key").and()
            .subject(user.toString()).issuer("setu").audience().add("setu-api").and()
            .issuedAt(new Date()).expiration(Date.from(Instant.now().plusSeconds(600))).id(UUID.randomUUID().toString());
        if (family != null) {
            builder.claim("fam", family.toString());
        }
        return builder.signWith(IDENTITY_KEY.getPrivate(), Jwts.SIG.ES256).compact();
    }

    @Test
    void theGatewayHasNoDatabaseNoSecurityChainAndNoOtherServicesCode() {
        assertThat(context.getBeanNamesForType(DataSource.class)).isEmpty();
        assertThat(context.getBeanNamesForType(EntityManagerFactory.class)).isEmpty();
        assertThat(context.containsBean("springSecurityFilterChain")).isFalse();
        var types = Arrays.stream(context.getBeanDefinitionNames()).map(context::getType).filter(t -> t != null).map(Class::getName).toList();
        assertThat(types.stream().filter(n -> n.startsWith("com.setu.")))
            .allMatch(n -> n.startsWith("com.setu.gateway.") || n.startsWith("com.setu.shared."), "only gateway and kernel classes");
        assertThat(types).noneMatch(n -> n.startsWith("com.setu.identity.") || n.startsWith("com.setu.institution.") || n.startsWith("com.setu.shared.admin.")
            || n.startsWith("com.setu.shared.seed."));
    }

    @Test
    void itsOwnHealthEndpointIsServedLocallyNotForwarded() throws Exception {
        assertThat(mockMvc.perform(get("/actuator/health")).andReturn().getResponse().getStatus()).isEqualTo(200);
        assertThat(stub.seen).isEmpty();
    }

    @Test
    void signInIsForwardedWithoutATokenToTheIdentityService() throws Exception {
        MockHttpServletResponse response = mockMvc.perform(post("/auth/login").contentType("application/json").content("{\"email\":\"a@b.c\"}"))
            .andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).isEqualTo("{\"ok\":true}");
        assertThat(stub.seen).hasSize(1);
        assertThat(stub.seen.get(0).requestLine()).isEqualTo("POST /auth/login HTTP/1.1");
        assertThat(stub.seen.get(0).body()).isEqualTo("{\"email\":\"a@b.c\"}");
        assertThat(stub.seen.get(0).header("X-Forwarded-For")).isNotBlank();
        assertThat(stub.seen.get(0).header("X-Correlation-Id")).isEqualTo(response.getHeader("X-Correlation-Id"));
    }

    @Test
    void aSignedInRequestReachesTheAcademicServiceWithItsOriginalToken() throws Exception {
        String bearer = token(UUID.randomUUID(), null);
        MockHttpServletResponse response = mockMvc.perform(get("/institution/colleges?x=1").header("Authorization", "Bearer " + bearer))
            .andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(stub.seen.get(0).requestLine()).isEqualTo("GET /institution/colleges?x=1 HTTP/1.1");
        assertThat(stub.seen.get(0).header("Authorization")).isEqualTo("Bearer " + bearer);
    }

    @Test
    void anUnauthenticatedOrRevokedRequestNeverReachesAService() throws Exception {
        assertThat(mockMvc.perform(get("/institution/colleges")).andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(mockMvc.perform(get("/institution/colleges").header("Authorization", "Bearer " + token(UUID.randomUUID(), null) + "x"))
            .andReturn().getResponse().getStatus()).isEqualTo(401);

        UUID family = UUID.randomUUID();
        String bearer = token(UUID.randomUUID(), family);
        assertThat(mockMvc.perform(get("/institution/colleges").header("Authorization", "Bearer " + bearer)).andReturn().getResponse().getStatus()).isEqualTo(200);
        stub.seen.clear();
        revocations.revokeLogin(family);
        assertThat(mockMvc.perform(get("/institution/colleges").header("Authorization", "Bearer " + bearer)).andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(stub.seen).isEmpty();
    }

    @Test
    void aServiceThatRefusesConnectionsIs502WithTheStandardEnvelope() throws Exception {
        MockHttpServletResponse response = mockMvc.perform(get("/learning/modules").header("Authorization", "Bearer " + token(UUID.randomUUID(), null)))
            .andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(502);
        assertThat(response.getContentAsString()).contains("\"code\":\"BAD_GATEWAY\"").contains("requestId");
    }

    @Test
    void theServicesErrorsPassThroughUnchanged() throws Exception {
        stub.response = "HTTP/1.1 403 Forbidden\r\nContent-Type: application/json\r\nContent-Length: 20\r\n\r\n{\"code\":\"FORBIDDEN\"}";
        MockHttpServletResponse response = mockMvc.perform(get("/institution/colleges").header("Authorization", "Bearer " + token(UUID.randomUUID(), null)))
            .andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).isEqualTo("{\"code\":\"FORBIDDEN\"}");
    }
}
