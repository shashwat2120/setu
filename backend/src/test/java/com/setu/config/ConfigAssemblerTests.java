package com.setu.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** How an application's configuration is built from files: the layers, their order, and that a name can never reach outside the two directories. */
class ConfigAssemblerTests {

    @TempDir
    Path root;
    Path config;
    Path secrets;
    ConfigAssembler assembler;

    @BeforeEach
    void setUp() throws IOException {
        config = Files.createDirectory(root.resolve("config"));
        secrets = Files.createDirectory(root.resolve("secrets"));
        assembler = new ConfigAssembler(config, secrets);
    }

    private void write(Path dir, String name, String yaml) throws IOException {
        Files.writeString(dir.resolve(name), yaml);
    }

    @Test
    void layersApplyInThePromisedOrderEachOverridingTheOneBefore() throws IOException {
        write(config, "application.yml", "k:\n  a: shared\n  b: shared\n  c: shared\n  d: shared\n");
        write(config, "application-local.yml", "k:\n  b: shared-local\n  c: shared-local\n  d: shared-local\n");
        write(config, "identity.yml", "k:\n  c: app\n  d: app\n");
        write(config, "identity-local.yml", "k:\n  d: app-local\n");

        var assembled = assembler.assemble("identity", List.of("local"));

        assertThat(assembled.properties()).containsEntry("k.a", "shared").containsEntry("k.b", "shared-local")
            .containsEntry("k.c", "app").containsEntry("k.d", "app-local");
        assertThat(assembled.sources()).containsExactly("config/application.yml", "config/application-local.yml", "config/identity.yml", "config/identity-local.yml");
    }

    @Test
    void secretsLayerOnTopOfConfigurationEvenOverADefault() throws IOException {
        write(config, "application.yml", "spring:\n  datasource:\n    url: jdbc:postgresql://db/setu\n    password: not-the-real-one\n");
        write(secrets, "application.yml", "spring:\n  datasource:\n    password: the-real-one\n");
        write(secrets, "identity.yml", "app:\n  security:\n    jwt:\n      signing-key: SIGNING\n");

        var identity = assembler.assemble("identity", List.of());
        var academic = assembler.assemble("academic", List.of());

        assertThat(identity.properties()).containsEntry("spring.datasource.password", "the-real-one").containsEntry("app.security.jwt.signing-key", "SIGNING")
            .containsEntry("spring.datasource.url", "jdbc:postgresql://db/setu");
        assertThat(academic.properties()).containsEntry("spring.datasource.password", "the-real-one").doesNotContainKey("app.security.jwt.signing-key");
    }

    @Test
    void profilesApplyInTheOrderGivenSoTheLastWins() throws IOException {
        write(config, "application-local.yml", "mode: local\n");
        write(config, "application-demo.yml", "mode: demo\n");
        assertThat(assembler.assemble("identity", List.of("local", "demo")).properties()).containsEntry("mode", "demo");
        assertThat(assembler.assemble("identity", List.of("demo", "local")).properties()).containsEntry("mode", "local");
    }

    @Test
    void listsAreFlattenedWithIndexesAndPlaceholdersAreLeftForTheReceiver() throws IOException {
        write(config, "gateway.yml", "app:\n  origins:\n    - http://a\n    - http://b\n  url: ${GATEWAY_URL:http://localhost:1}\n  port: 8080\n");
        var props = assembler.assemble("gateway", List.of()).properties();
        assertThat(props).containsEntry("app.origins[0]", "http://a").containsEntry("app.origins[1]", "http://b")
            .containsEntry("app.url", "${GATEWAY_URL:http://localhost:1}").containsEntry("app.port", "8080");
    }

    @Test
    void missingFilesAreSkippedAndAnEmptyDirectoryServesAnEmptyConfiguration() {
        var assembled = assembler.assemble("identity", List.of("local"));
        assertThat(assembled.properties()).isEmpty();
        assertThat(assembled.sources()).isEmpty();
    }

    @Test
    void theVersionChangesWithAnyValueAndOnlyWithValues() throws IOException {
        write(config, "identity.yml", "a: 1\nb: 2\n");
        String first = assembler.assemble("identity", List.of()).version();
        assertThat(assembler.assemble("identity", List.of()).version()).isEqualTo(first);
        write(config, "identity.yml", "a: 1\nb: 3\n");
        assertThat(assembler.assemble("identity", List.of()).version()).isNotEqualTo(first).hasSize(12);
        write(config, "identity.yml", "b: 3\na: 1\n");   // same content, different order
        assertThat(assembler.assemble("identity", List.of()).version()).isEqualTo(assembler.assemble("identity", List.of()).version());
    }

    @Test
    void namesThatCouldReachOutsideTheDirectoriesAreRefused() throws IOException {
        write(root, "evil.yml", "stolen: yes\n");
        for (String bad : new String[] {"../evil", "..\\evil", "a/b", "identity.yml", "Identity", "", "-x", "a".repeat(80), "id entity", "id\u0000"}) {
            assertThatThrownBy(() -> assembler.assemble(bad, List.of())).as(bad).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> assembler.assemble("identity", List.of(bad))).as(bad).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
