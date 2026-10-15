package com.setu.shared.config;

import com.setu.config.ConfigAssembler;
import com.setu.config.ConfigController;
import com.setu.config.ConfigServerProperties;
import com.setu.testsupport.BlockingStubServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * A service importing its configuration from the config server, end to end: the answer served over a real socket is produced by the real
 * {@link ConfigController}, and the importing side is a real Spring application start. Covers what an operator relies on: values arrive, the
 * environment still wins, a missing server is survivable only when the import says {@code optional:}, and a refused token is never survivable.
 */
class SetuConfigDataTests {

    private static final String TOKEN = "academic-token-0123456789abcdef";

    @Configuration
    static class Empty {
    }

    @TempDir
    Path root;
    BlockingStubServer server;

    @BeforeEach
    void setUp() throws Exception {
        server = new BlockingStubServer();
        Path config = Files.createDirectory(root.resolve("config"));
        Path secrets = Files.createDirectory(root.resolve("secrets"));
        Files.writeString(config.resolve("application.yml"), "app:\n  issuer: from-server\n  note: shared\n");
        Files.writeString(config.resolve("application-local.yml"), "app:\n  note: local-profile\n");
        Files.writeString(secrets.resolve("academic.yml"), "app:\n  secret-thing: s3cr3t-from-server\n");
        ConfigServerProperties properties = new ConfigServerProperties(config, secrets, Map.of("academic", TOKEN));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ConfigController(new ConfigAssembler(config, secrets), properties))
            .setMessageConverters(new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter(
                new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules())).build();
        // What the real server would answer for the token this test will send, served over a real socket.
        String json = mvc.perform(get("/config/academic/local").header("Authorization", "Bearer " + TOKEN)).andReturn().getResponse().getContentAsString();
        server.response = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: " + json.getBytes(StandardCharsets.UTF_8).length + "\r\n\r\n" + json;
    }

    @AfterEach
    void tearDown() throws IOException {
        server.close();
    }

    private ConfigurableApplicationContext start(String importLocation, String... extraArgs) {
        String[] args = new String[extraArgs.length + 4];
        args[0] = "--spring.config.import=" + importLocation;
        args[1] = "--spring.application.name=academic-service";
        args[2] = "--spring.profiles.active=local";
        args[3] = "--spring.main.banner-mode=off";
        System.arraycopy(extraArgs, 0, args, 4, extraArgs.length);
        return new SpringApplicationBuilder(Empty.class).web(WebApplicationType.NONE).run(args);
    }

    @Test
    void valuesAndSecretsArriveForTheRightApplicationAndProfilesWithTheToken() {
        try (ConfigurableApplicationContext context = start("setu-config:" + server.uri(), "--setu.config.token=" + TOKEN)) {
            assertThat(context.getEnvironment().getProperty("app.issuer")).isEqualTo("from-server");
            assertThat(context.getEnvironment().getProperty("app.note")).isEqualTo("local-profile");
            assertThat(context.getEnvironment().getProperty("app.secret-thing")).isEqualTo("s3cr3t-from-server");
        }
        var seen = server.seen.get(0);
        assertThat(seen.requestLine()).isEqualTo("GET /config/academic/local HTTP/1.1");   // "-service" stripped from the application name
        assertThat(seen.header("Authorization")).isEqualTo("Bearer " + TOKEN);
    }

    @Test
    void theEnvironmentAndCommandLineStillWinOverTheServer() {
        try (ConfigurableApplicationContext context = start("setu-config:" + server.uri(), "--setu.config.token=" + TOKEN, "--app.issuer=operator-override")) {
            assertThat(context.getEnvironment().getProperty("app.issuer")).isEqualTo("operator-override");
            assertThat(context.getEnvironment().getProperty("app.note")).isEqualTo("local-profile");
        }
    }

    @Test
    void theApplicationNameCanBeSetExplicitly() {
        try (ConfigurableApplicationContext context = start("setu-config:" + server.uri(), "--setu.config.token=" + TOKEN, "--setu.config.application=academic")) {
            assertThat(context.getEnvironment().getProperty("app.issuer")).isEqualTo("from-server");
        }
    }

    @Test
    void aRefusedTokenFailsStartUpEvenWhenTheImportIsOptional() {
        server.response = "HTTP/1.1 401 Unauthorized\r\nContent-Length: 0\r\n\r\n";
        assertThatThrownBy(() -> start("optional:setu-config:" + server.uri(), "--setu.config.token=wrong-token-0123456789abcdef"))
            .hasStackTraceContaining("refused").hasStackTraceContaining("SETU_CONFIG_TOKEN").satisfies(e -> assertThat(stack(e)).doesNotContain("wrong-token-0123456789abcdef"));
        server.response = "HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\n\r\n";
        assertThatThrownBy(() -> start("optional:setu-config:" + server.uri(), "--setu.config.token=" + TOKEN)).hasStackTraceContaining("refused");
    }

    @Test
    void anUnreachableServerIsSurvivableOnlyWhenTheImportIsOptional() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        String url = "http://127.0.0.1:" + closedPort;
        try (ConfigurableApplicationContext context = start("optional:setu-config:" + url, "--setu.config.token=" + TOKEN, "--app.issuer=local-default")) {
            assertThat(context.getEnvironment().getProperty("app.issuer")).as("started on what it already had").isEqualTo("local-default");
        }
        assertThatThrownBy(() -> start("setu-config:" + url, "--setu.config.token=" + TOKEN)).isNotNull();
    }

    @Test
    void anUnknownApplicationIsAnOptionalMiss() {
        server.response = "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n";
        try (ConfigurableApplicationContext context = start("optional:setu-config:" + server.uri(), "--setu.config.token=" + TOKEN, "--app.issuer=kept")) {
            assertThat(context.getEnvironment().getProperty("app.issuer")).isEqualTo("kept");
        }
    }

    @Test
    void aMalformedLocationIsRejectedWithAClearMessage() {
        assertThatThrownBy(() -> start("setu-config:not-a-url")).hasStackTraceContaining("http(s) URL");
        assertThatThrownBy(() -> start("setu-config:ftp://host/x")).hasStackTraceContaining("http(s) URL");
    }

    @Test
    void nothingChangesForAnApplicationThatDoesNotImportIt() {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(Empty.class).web(WebApplicationType.NONE)
            .run("--spring.main.banner-mode=off", "--app.issuer=plain")) {
            assertThat(context.getEnvironment().getProperty("app.issuer")).isEqualTo("plain");
        }
        assertThat(server.seen).isEmpty();
    }

    private static String stack(Throwable t) {
        java.io.StringWriter out = new java.io.StringWriter();
        t.printStackTrace(new java.io.PrintWriter(out));
        return out.toString();
    }
}
