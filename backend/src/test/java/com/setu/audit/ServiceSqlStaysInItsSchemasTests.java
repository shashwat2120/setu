package com.setu.audit;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The other half of the database logins ({@code ServiceDatabaseRolesTests} shows the database refuses): the code of a service must not ask for what its login would refuse.
 * The suites run as the database owner, so they cannot notice a query that reaches into another service's schema; this reads the SQL text of every package a service
 * scans (its own modules and the shared kernel) and fails if any names a schema the service does not own. JPA entities are held to their own schema by
 * {@code TableOwnershipTests}; this covers hand-written SQL.
 */
class ServiceSqlStaysInItsSchemasTests {

    private static final List<String> SCHEMAS = List.of("identity", "institution", "catalog", "directory", "enrollment", "guardian_access", "learning", "assessment", "attendance",
        "notification", "record", "finance", "career", "risk", "analytics", "governance");

    /** The service logins' schemas (service-roles.sql); governance is the cross-cutting platform schema every process uses. */
    private static final Map<String, Set<String>> ALLOWED = Map.of(
        "identity", Set.of("identity", "governance"),
        "academic", Set.of("catalog", "directory", "enrollment", "guardian_access", "institution", "governance"),
        "learning", Set.of("learning", "governance"),
        "assessment", Set.of("assessment", "governance"),
        "attendance", Set.of("attendance", "governance"),
        "notification", Set.of("notification", "governance"));

    /** The source packages each service process scans, besides the shared kernel (see each XServiceApplication). */
    private static final Map<String, List<String>> PACKAGES = Map.of(
        "identity", List.of("identity"),
        "academic", List.of("academic", "institution", "catalog", "enrollment", "directory", "guardianaccess"),
        "learning", List.of("learning"),
        "assessment", List.of("assessment"),
        "attendance", List.of("attendance"),
        "notification", List.of("notification"));

    private static final Pattern STRING = Pattern.compile("\"\"\"[\\s\\S]*?\"\"\"|\"(?:[^\"\\\\\\n]|\\\\.)*\"");
    private static final Pattern LOOKS_LIKE_SQL = Pattern.compile("\\b(select|insert|update|delete|from|join|into)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern SCHEMA_REFERENCE = Pattern.compile("(?<![A-Za-z_.])(" + String.join("|", SCHEMAS) + ")\\.([a-z_]{3,})\\b");

    private static List<Path> javaFiles(String pkg) throws IOException {
        Path root = Path.of("src/main/java/com/setu", pkg);
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }

    @Test
    void noServiceNamesAnotherServicesSchemaInItsSql() throws IOException {
        List<String> found = new ArrayList<>();
        for (var service : PACKAGES.entrySet()) {
            List<Path> files = new ArrayList<>();
            for (String pkg : service.getValue()) {
                files.addAll(javaFiles(pkg));
            }
            files.addAll(javaFiles("shared"));
            assertThat(files).as(service.getKey() + " has source to read").isNotEmpty();
            for (Path file : files) {
                Matcher strings = STRING.matcher(Files.readString(file));
                while (strings.find()) {
                    String text = strings.group();
                    if (!LOOKS_LIKE_SQL.matcher(text).find()) {
                        continue;
                    }
                    Matcher ref = SCHEMA_REFERENCE.matcher(text);
                    while (ref.find()) {
                        if (!ALLOWED.get(service.getKey()).contains(ref.group(1))) {
                            found.add(service.getKey() + ": " + file.getFileName() + " names " + ref.group());
                        }
                    }
                }
            }
        }
        assertThat(found).as("SQL that the service's own database login would be refused").isEmpty();
    }

    @Test
    void theScanSeesSqlAtAll() throws IOException {
        long own = 0;
        for (Path file : javaFiles("notification")) {
            Matcher strings = STRING.matcher(Files.readString(file));
            while (strings.find()) {
                if (LOOKS_LIKE_SQL.matcher(strings.group()).find() && SCHEMA_REFERENCE.matcher(strings.group()).find()) {
                    own++;
                }
            }
        }
        assertThat(own).as("schema-qualified SQL in the notification module (so an empty result above means something)").isGreaterThan(0);
    }
}
