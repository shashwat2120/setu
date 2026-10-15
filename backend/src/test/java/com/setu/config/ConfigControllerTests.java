package com.setu.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** Who may read what: each application only its own configuration, with its own token, and nothing leaks in an error. */
class ConfigControllerTests {

    private static final String IDENTITY_TOKEN = "identity-token-0123456789abcdef";
    private static final String ACADEMIC_TOKEN = "academic-token-0123456789abcdef";

    @TempDir
    Path root;
    MockMvc mvc;
    final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @BeforeEach
    void setUp() throws IOException {
        Path config = Files.createDirectory(root.resolve("config"));
        Path secrets = Files.createDirectory(root.resolve("secrets"));
        Files.writeString(config.resolve("application.yml"), "app:\n  issuer: setu\n");
        Files.writeString(config.resolve("application-local.yml"), "spring:\n  host: localhost\n");
        Files.writeString(secrets.resolve("identity.yml"), "app:\n  signing-key: TOP-SECRET-SIGNING-KEY\n");
        Files.writeString(secrets.resolve("academic.yml"), "app:\n  other: academic-only\n");
        ConfigServerProperties properties = new ConfigServerProperties(config, secrets, Map.of("identity", IDENTITY_TOKEN, "academic", ACADEMIC_TOKEN));
        mvc = MockMvcBuilders.standaloneSetup(new ConfigController(new ConfigAssembler(config, secrets), properties))
            .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper)).build();
    }

    private MockHttpServletResponse fetch(String path, String token) throws Exception {
        var request = get(path);
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return mvc.perform(request).andReturn().getResponse();
    }

    @Test
    void anApplicationReadsItsOwnConfigurationAndSecretsWithItsOwnToken() throws Exception {
        MockHttpServletResponse response = fetch("/config/identity/local", IDENTITY_TOKEN);
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader("Cache-Control")).contains("no-store");
        JsonNode body = objectMapper.readTree(response.getContentAsString());
        assertThat(body.get("application").asText()).isEqualTo("identity");
        assertThat(body.get("profiles").get(0).asText()).isEqualTo("local");
        assertThat(body.get("version").asText()).hasSize(12);
        assertThat(body.get("properties").get("app.issuer").asText()).isEqualTo("setu");
        assertThat(body.get("properties").get("spring.host").asText()).isEqualTo("localhost");
        assertThat(body.get("properties").get("app.signing-key").asText()).isEqualTo("TOP-SECRET-SIGNING-KEY");
    }

    @Test
    void theProfileSegmentIsOptionalAndSeveralProfilesAreComaSeparated() throws Exception {
        assertThat(objectMapper.readTree(fetch("/config/identity", IDENTITY_TOKEN).getContentAsString()).get("properties").has("spring.host")).isFalse();
        assertThat(objectMapper.readTree(fetch("/config/identity/default", IDENTITY_TOKEN).getContentAsString()).get("properties").has("spring.host")).isFalse();
        assertThat(objectMapper.readTree(fetch("/config/identity/local,demo", IDENTITY_TOKEN).getContentAsString()).get("properties").has("spring.host")).isTrue();
    }

    @Test
    void noTokenAndAnUnknownTokenAreBothUnauthenticated() throws Exception {
        assertThat(fetch("/config/identity/local", null).getStatus()).isEqualTo(401);
        assertThat(fetch("/config/identity/local", "not-a-token-at-all-0123456789").getStatus()).isEqualTo(401);
        var basic = mvc.perform(get("/config/identity/local").header("Authorization", "Basic abc")).andReturn().getResponse();
        assertThat(basic.getStatus()).isEqualTo(401);
    }

    @Test
    void anotherApplicationsTokenCannotReadIdentitysSecrets() throws Exception {
        MockHttpServletResponse response = fetch("/config/identity/local", ACADEMIC_TOKEN);
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).doesNotContain("TOP-SECRET").doesNotContain("signing-key");
        assertThat(fetch("/config/academic/local", ACADEMIC_TOKEN).getStatus()).isEqualTo(200);
    }

    @Test
    void anApplicationNobodyConfiguredATokenForCannotBeFetchedAtAll() throws Exception {
        assertThat(fetch("/config/payroll/local", IDENTITY_TOKEN).getStatus()).isEqualTo(403);
    }

    @Test
    void badNamesAreRejectedAfterAuthenticationWithoutTouchingTheFileSystem() throws Exception {
        // Authenticated as identity, asking for identity's files with a traversal in the profile segment.
        assertThat(fetch("/config/identity/..%2F..%2Fetc", IDENTITY_TOKEN).getStatus()).isIn(400, 404);
        assertThat(fetch("/config/identity/UPPER", IDENTITY_TOKEN).getStatus()).isEqualTo(400);
    }

    @Test
    void errorBodiesNameNeitherTokensNorSecretsNorPaths() throws Exception {
        for (MockHttpServletResponse r : new MockHttpServletResponse[] {fetch("/config/identity/local", null), fetch("/config/identity/local", ACADEMIC_TOKEN),
            fetch("/config/identity/UPPER", IDENTITY_TOKEN)}) {
            assertThat(r.getContentAsString()).doesNotContain(IDENTITY_TOKEN).doesNotContain(ACADEMIC_TOKEN).doesNotContain(root.toString())
                .contains("requestId");
        }
    }
}
