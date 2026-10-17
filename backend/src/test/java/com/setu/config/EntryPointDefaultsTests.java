package com.setu.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.FileSystemResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Start-up defaults that silently do nothing. Every service's entry point gives Spring Boot a map of defaults, and those have the LOWEST
 * precedence of any property source (EntryPointDefaultsPrecedenceTests shows it), below the packaged application.yml. A default for a key the yml
 * also sets is therefore ignored, with no error: for a long time every service's `server.port` default was shadowed this way (they would all have
 * started on 8080) and `spring.application.name` too (they would all have called themselves setu-backend in metrics and traces).
 * <p>
 * The rule the entry points follow, enforced here by reading the source: a default is either for a key the yml does not set, or it is for an
 * UPPER_CASE placeholder that the yml reads (`SERVER_PORT`, `APPLICATION_NAME`, `SERVER_MAX_THREADS`), which does work.
 */
class EntryPointDefaultsTests {

    private static final Path MAIN = Path.of("src/main/java");
    private static final Path YML = Path.of("src/main/resources/application.yml");
    private static final Pattern PAIR = Pattern.compile("\"([A-Za-z0-9_.\\-]+)\",\\s*(?:\"[^\"]*\"|[A-Za-z_]+)");

    private static Map<String, String> entryPointDefaults() throws IOException {
        Map<String, String> keysByFile = new TreeMap<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(p -> p.getFileName().toString().endsWith("Application.java")).toList()) {
                String text = Files.readString(file, StandardCharsets.UTF_8);
                int main = text.indexOf("public static void main");
                if (main < 0 || !text.contains("setDefaultProperties")) {
                    continue;
                }
                Matcher m = PAIR.matcher(text.substring(main));
                while (m.find()) {
                    keysByFile.put(file.getFileName() + " :: " + m.group(1), m.group(1));
                }
            }
        }
        return keysByFile;
    }

    @Test
    void noDefaultIsShadowedByTheYmlAndEveryPlaceholderDefaultIsReadByIt() throws IOException {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new FileSystemResource(YML.toFile()));
        Properties ymlProperties = yaml.getObject();
        String ymlText = Files.readString(YML, StandardCharsets.UTF_8);

        Map<String, String> defaults = entryPointDefaults();
        assertThat(defaults.values().stream().distinct().count()).as("the scan finds the entry points' defaults").isGreaterThan(8);

        Set<String> shadowed = new TreeSet<>();
        Set<String> unread = new TreeSet<>();
        for (var entry : defaults.entrySet()) {
            String key = entry.getValue();
            if (key.equals(key.toUpperCase())) {
                if (!ymlText.contains("${" + key)) {
                    unread.add(entry.getKey());
                }
            } else if (ymlProperties.containsKey(key)) {
                shadowed.add(entry.getKey());
            }
        }
        assertThat(shadowed).as("defaults for keys application.yml also sets (ignored: set the yml's placeholder instead)").isEmpty();
        assertThat(unread).as("placeholder defaults that application.yml never reads").isEmpty();
    }

    @Test
    void everyServiceHasItsOwnPortAndItsOwnName() throws IOException {
        Pattern service = Pattern.compile("\"setu\\.service\",\\s*\"([a-z]+)\"");
        Pattern port = Pattern.compile("\"SERVER_PORT\",\\s*\"(\\d+)\"");
        Pattern name = Pattern.compile("\"APPLICATION_NAME\",\\s*\"([a-z-]+)\"");
        Map<String, String> portsByService = new TreeMap<>();
        Set<String> names = new TreeSet<>();
        int entryPoints = 0;
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(p -> p.getFileName().toString().endsWith("Application.java")).toList()) {
                String text = Files.readString(file, StandardCharsets.UTF_8);
                Matcher s = service.matcher(text);
                Matcher p = port.matcher(text);
                Matcher n = name.matcher(text);
                if (s.find() && p.find() && n.find()) {
                    entryPoints++;
                    assertThat(portsByService.put(s.group(1), p.group(1))).as("service listed twice: " + s.group(1)).isNull();
                    assertThat(names.add(n.group(1))).as("two services called " + n.group(1)).isTrue();
                }
            }
        }
        assertThat(entryPoints).isGreaterThanOrEqualTo(8);
        assertThat(portsByService.values().stream().collect(Collectors.toSet())).as("no two services on one port").hasSize(portsByService.size());
    }
}
