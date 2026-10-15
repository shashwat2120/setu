package com.setu.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The server refuses to start in any state that would serve secrets carelessly, and its messages never repeat a token. */
class ConfigServerConfigurationTests {

    private static final String GOOD = "a-long-enough-random-token-0123456789";

    @TempDir
    Path dir;

    private ConfigAssembler start(Path config, Map<String, String> tokens) {
        return new ConfigServerConfiguration().configAssembler(new ConfigServerProperties(config, dir.resolve("no-secrets"), tokens));
    }

    @Test
    void startsWithOneLongTokenPerApplicationAndAnExistingConfigDirectory() {
        assertThat(start(dir, Map.of("identity", GOOD))).isNotNull();
    }

    @Test
    void refusesToStartWithNoTokensAtAll() {
        assertThatThrownBy(() -> start(dir, Map.of())).isInstanceOf(IllegalStateException.class).hasMessageContaining("tokens");
    }

    @Test
    void refusesATokenThatIsTooShortWithoutEchoingIt() {
        assertThatThrownBy(() -> start(dir, Map.of("identity", "short-secret-1")))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("identity").hasMessageNotContaining("short-secret-1");
    }

    @Test
    void refusesAnApplicationNameThatCouldNotBeAFileName() {
        assertThatThrownBy(() -> start(dir, Map.of("../x", GOOD))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void refusesAMissingConfigDirectoryInsteadOfServingNothing() {
        assertThatThrownBy(() -> start(dir.resolve("absent"), Map.of("identity", GOOD))).isInstanceOf(IllegalStateException.class).hasMessageContaining("does not exist");
    }

    @Test
    void aMissingSecretsDirectoryIsOnlyAWarning() throws Exception {
        Files.createDirectory(dir.resolve("config"));
        assertThat(start(dir.resolve("config"), Map.of("identity", GOOD))).isNotNull();
    }
}
