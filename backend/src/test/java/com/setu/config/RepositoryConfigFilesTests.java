package com.setu.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.FileSystemResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Secrets stay out of the repository: nothing in the committed configuration may hold a secret's value. A key whose last part names a
 * secret (password, secret, signing key, API key, token, credential) may be empty, a bare environment placeholder {@code ${NAME}} or
 * {@code ${NAME:}} (no default), or, in {@code secrets.example/}, the word CHANGE_ME. The backend's own {@code application.yml} predates the
 * config server and carries exactly two documented dev-only defaults; that list can shrink but not grow.
 */
class RepositoryConfigFilesTests {

    private static final Path CONFIG = Path.of("../infra/config");
    private static final Path APPLICATION_YML = Path.of("src/main/resources/application.yml");
    private static final Pattern SECRET_LEAF = Pattern.compile("(?i)(^|[.\\-\\[\\]])(password|secret|signing-key|api-key|private-key|credentials?|token)$");
    private static final Pattern ENV_PLACEHOLDER = Pattern.compile("\\$\\{[A-Z][A-Z0-9_]*:?}");

    /** The only dev-only defaults still in application.yml; each is labelled DEV-ONLY there and overridden by every real deployment. */
    private static final Set<String> KNOWN_DEV_DEFAULTS = Set.of("spring.datasource.password", "app.security.jwt.secret");

    private static Properties flatten(Path file) {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new FileSystemResource(file));
        Properties properties = yaml.getObject();
        return properties == null ? new Properties() : properties;
    }

    private static Set<String> secretsWithLiteralValues(Path file, boolean allowChangeMe) {
        Set<String> bad = new TreeSet<>();
        flatten(file).forEach((k, v) -> {
            String key = String.valueOf(k);
            String value = String.valueOf(v).strip();
            if (SECRET_LEAF.matcher(key).find() && !value.isEmpty() && !ENV_PLACEHOLDER.matcher(value).matches()
                && !(allowChangeMe && value.equals("CHANGE_ME"))) {
                bad.add(file.getFileName() + ": " + key);   // the key, never the value
            }
        });
        return bad;
    }

    @Test
    void theCommittedConfigurationHoldsNoSecretValues() throws IOException {
        assertThat(CONFIG).as("infra/config exists next to backend/").isDirectory();
        Set<String> bad = new TreeSet<>();
        try (Stream<Path> files = Files.walk(CONFIG)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".yml")).toList()) {
                boolean example = file.toString().replace('\\', '/').contains("/secrets.example/");
                boolean realSecrets = file.toString().replace('\\', '/').contains("/secrets/");
                if (!realSecrets) {   // the real secrets directory is git-ignored; if it exists locally it is meant to hold values
                    bad.addAll(secretsWithLiteralValues(file, example));
                }
            }
        }
        assertThat(bad).as("secret-looking keys with a literal value in committed config").isEmpty();
    }

    @Test
    void theExampleSecretsNameWhatEachServiceNeedsToBeSeeded() {
        Properties application = flatten(CONFIG.resolve("secrets.example/application.yml"));
        Properties identity = flatten(CONFIG.resolve("secrets.example/identity.yml"));
        assertThat(application.stringPropertyNames()).contains("spring.datasource.password", "app.security.jwt.secret");
        assertThat(identity.stringPropertyNames()).contains("app.security.jwt.signing-key");
        assertThat(application.stringPropertyNames()).as("Identity alone holds the token-signing key").doesNotContain("app.security.jwt.signing-key");
    }

    @Test
    void theRealSecretsDirectoryIsIgnoredByGit() throws IOException {
        String ignore = Files.readString(Path.of("../.gitignore"));
        assertThat(ignore).contains("infra/config/secrets/");
    }

    @Test
    void applicationYmlCarriesNoSecretDefaultsBeyondTheTwoDocumentedDevOnlyOnes() {
        Set<String> withDefaults = new TreeSet<>();
        flatten(APPLICATION_YML).forEach((k, v) -> {
            String key = String.valueOf(k);
            String value = String.valueOf(v).strip();
            // ${NAME:default}: a secret-looking key with a non-empty default is a secret in the repository
            var m = Pattern.compile("\\$\\{[A-Z][A-Z0-9_]*:([^}]+)}").matcher(value);
            if (SECRET_LEAF.matcher(key).find() && (m.matches() || (!value.isEmpty() && !value.startsWith("${")))) {
                withDefaults.add(key);
            }
        });
        assertThat(withDefaults).isSubsetOf(KNOWN_DEV_DEFAULTS);
    }

    @Test
    void everyServiceProfileAssemblesFromTheCommittedFiles() {
        ConfigAssembler assembler = new ConfigAssembler(CONFIG, Path.of("no-secrets-here"));
        for (String application : new String[] {"identity", "academic", "gateway"}) {
            for (var profiles : new java.util.List[] {java.util.List.of(), java.util.List.of("local"), java.util.List.of("local", "demo")}) {
                @SuppressWarnings("unchecked")
                var assembled = assembler.assemble(application, (java.util.List<String>) profiles);
                assertThat(assembled.properties()).as("%s %s", application, profiles).containsKey("app.security.jwt.issuer");
            }
        }
        assertThat(assembler.assemble("identity", java.util.List.of("local")).properties()).containsEntry("server.port", "8081");
        assertThat(assembler.assemble("academic", java.util.List.of("local")).properties()).containsEntry("server.port", "8082");
        assertThat(assembler.assemble("gateway", java.util.List.of()).properties()).containsEntry("server.port", "8080");
        assertThat(assembler.assemble("identity", java.util.List.of("local", "demo")).properties()).containsEntry("app.demo-data.enabled", "true");
        assertThat(assembler.assemble("identity", java.util.List.of("local")).properties()).doesNotContainKey("app.demo-data.enabled");
    }
}
