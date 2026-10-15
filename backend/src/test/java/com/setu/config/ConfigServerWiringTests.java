package com.setu.config;

import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * The config server as a whole application, serving the repository's real {@code infra/config} with a secret from a separate directory.
 * No database, no Redis, no messaging, no other module's code.
 */
@SpringBootTest(classes = ConfigServerApplication.class, properties = {"setu.service=config"})
@AutoConfigureMockMvc
class ConfigServerWiringTests {

    private static final String IDENTITY_TOKEN = "identity-token-0123456789abcdef";
    private static final String ACADEMIC_TOKEN = "academic-token-0123456789abcdef";
    private static Path secrets;

    @DynamicPropertySource
    static void server(DynamicPropertyRegistry registry) throws IOException {
        secrets = Files.createTempDirectory("setu-secrets");
        Files.writeString(secrets.resolve("identity.yml"), "app:\n  security:\n    jwt:\n      signing-key: SECRET-SIGNING-KEY-VALUE\n");
        registry.add("app.configserver.configdir", () -> "../infra/config");
        registry.add("app.configserver.secretsdir", () -> secrets.toString());
        registry.add("app.configserver.tokens.identity", () -> IDENTITY_TOKEN);
        registry.add("app.configserver.tokens.academic", () -> ACADEMIC_TOKEN);
    }

    @Autowired
    ApplicationContext context;
    @Autowired
    MockMvc mockMvc;

    @Test
    void servesTheRealConfigurationWithTheSecretLayeredOn() throws Exception {
        String body = mockMvc.perform(get("/config/identity/local,demo").header("Authorization", "Bearer " + IDENTITY_TOKEN)).andReturn().getResponse().getContentAsString();
        assertThat(body).contains("\"app.security.jwt.signing-key\":\"SECRET-SIGNING-KEY-VALUE\"").contains("\"server.port\":\"8081\"")
            .contains("\"spring.datasource.url\":\"jdbc:postgresql://localhost:5433/setu\"").contains("\"app.demo-data.enabled\":\"true\"");
    }

    @Test
    void anotherApplicationNeverSeesIdentitysSecret() throws Exception {
        var forbidden = mockMvc.perform(get("/config/identity/local").header("Authorization", "Bearer " + ACADEMIC_TOKEN)).andReturn().getResponse();
        assertThat(forbidden.getStatus()).isEqualTo(403);
        String academic = mockMvc.perform(get("/config/academic/local").header("Authorization", "Bearer " + ACADEMIC_TOKEN)).andReturn().getResponse().getContentAsString();
        assertThat(academic).doesNotContain("SECRET-SIGNING-KEY-VALUE").contains("\"server.port\":\"8082\"");
    }

    @Test
    void itHasNoDatabaseAndNoOtherCode() {
        assertThat(context.getBeanNamesForType(DataSource.class)).isEmpty();
        assertThat(context.getBeanNamesForType(EntityManagerFactory.class)).isEmpty();
        var types = Arrays.stream(context.getBeanDefinitionNames()).map(context::getType).filter(t -> t != null).map(Class::getName).toList();
        assertThat(types.stream().filter(n -> n.startsWith("com.setu.")))
            .allMatch(n -> n.startsWith("com.setu.config.") || n.startsWith("com.setu.shared."), "only config server and kernel classes");
        assertThat(types).noneMatch(n -> n.startsWith("com.setu.identity.") || n.startsWith("com.setu.gateway."));
    }

    @Test
    void healthIsPublicEverythingElseNeedsATokenAndNothingIsCached() throws Exception {
        assertThat(mockMvc.perform(get("/actuator/health")).andReturn().getResponse().getStatus()).isEqualTo(200);
        var anonymous = mockMvc.perform(get("/config/identity/local")).andReturn().getResponse();
        assertThat(anonymous.getStatus()).isEqualTo(401);
        assertThat(anonymous.getHeader("Cache-Control")).contains("no-store");
    }
}
