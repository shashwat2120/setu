package com.setu.audit;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A service that has a database of its own migrates it itself (infra/db/README.md, "A service's own migrations"): from {@code infra/db/platform} (the tables every database
 * carries a copy of) and {@code infra/db/services/<service>} (what that service owns). The core applies every one of those folders to the shared database as well, so
 * the same file is applied once to each database and a version means the same thing in both histories. These tests hold the rules that keeps true, without a database.
 */
class ServiceMigrationsTests {

    /** The version the shared database stood at when the first service was split off; every file in the new folders is above it. */
    private static final int SPLIT_BASELINE = 103;
    private static final Path DB = Path.of("../infra/db");
    private static final Pattern FILE_NAME = Pattern.compile("V(\\d+)__[a-z0-9_]+\\.sql");
    private static final Pattern LINE_COMMENT = Pattern.compile("--[^\\n]*");
    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*[\\s\\S]*?\\*/");

    private static List<Path> sqlFiles(Path folder) throws IOException {
        if (!Files.isDirectory(folder)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(folder)) {
            return files.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".sql")).sorted().toList();
        }
    }

    private static List<Path> serviceFolders() throws IOException {
        Path root = DB.resolve("services");
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> dirs = Files.list(root)) {
            return dirs.filter(Files::isDirectory).sorted().toList();
        }
    }

    private static String sqlWithoutComments(Path file) throws IOException {
        String text = Files.readString(file, StandardCharsets.UTF_8);
        return LINE_COMMENT.matcher(BLOCK_COMMENT.matcher(text).replaceAll(" ")).replaceAll(" ");
    }

    /** owner (as named in table-owners.txt) -> the schemas it owns, and every schema named there. */
    private static Map<String, Set<String>> schemasByOwner(Set<String> allSchemas) throws IOException {
        Map<String, Set<String>> byOwner = new HashMap<>();
        for (String line : Files.readAllLines(Path.of("src/test/resources/table-owners.txt"), StandardCharsets.UTF_8)) {
            String l = line.strip();
            if (l.isEmpty() || l.startsWith("#")) {
                continue;
            }
            String[] parts = l.split("\\s+");
            String schema = parts[0].substring(0, parts[0].indexOf('.'));
            byOwner.computeIfAbsent(parts[1], k -> new TreeSet<>()).add(schema);
            allSchemas.add(schema);
        }
        return byOwner;
    }

    private static List<String> schemaReferences(String sql, Set<String> knownSchemas) {
        List<String> refs = new ArrayList<>();
        Matcher m = Pattern.compile("(?<![A-Za-z_.])(" + String.join("|", knownSchemas) + "|public)\\.([a-z_]+)").matcher(sql);
        while (m.find()) {
            refs.add(m.group(1) + "." + m.group(2));
        }
        return refs;
    }

    @Test
    void everyVersionIsUsedOnceAcrossTheMonolithsFolderThePlatformsAndEveryServices() throws IOException {
        Map<Integer, Path> seen = new HashMap<>();
        List<String> problems = new ArrayList<>();
        List<Path> folders = new ArrayList<>();
        folders.add(DB.resolve("migrations"));
        folders.add(DB.resolve("platform"));
        folders.addAll(serviceFolders());
        for (Path folder : folders) {
            for (Path file : sqlFiles(folder)) {
                Matcher m = FILE_NAME.matcher(file.getFileName().toString());
                if (!m.matches()) {
                    problems.add(file + " is not named V<number>__<lower_case_words>.sql");
                    continue;
                }
                int version = Integer.parseInt(m.group(1));
                Path earlier = seen.put(version, file);
                if (earlier != null) {
                    problems.add("version " + version + " is in both " + earlier + " and " + file);
                }
                if (!folder.getFileName().toString().equals("migrations") && version <= SPLIT_BASELINE) {
                    problems.add(file + " is at or below the split baseline " + SPLIT_BASELINE);
                }
            }
        }
        assertThat(problems).isEmpty();
        assertThat(seen).as("the monolith's migrations were found").hasSizeGreaterThan(100);
    }

    @Test
    void aServicesMigrationsTouchOnlyItsOwnSchemas() throws IOException {
        Set<String> all = new TreeSet<>();
        Map<String, Set<String>> byOwner = schemasByOwner(all);
        List<String> problems = new ArrayList<>();
        for (Path folder : serviceFolders()) {
            String name = folder.getFileName().toString();
            String owner = Character.toUpperCase(name.charAt(0)) + name.substring(1);
            Set<String> mine = byOwner.get(owner);
            assertThat(mine).as("services/" + name + " names an owner in table-owners.txt").isNotNull();
            assertThat(sqlFiles(folder)).as("services/" + name + " has migrations").isNotEmpty();
            for (Path file : sqlFiles(folder)) {
                String sql = sqlWithoutComments(file);
                for (String ref : schemaReferences(sql, all)) {
                    if (!mine.contains(ref.substring(0, ref.indexOf('.')))) {
                        problems.add(file.getFileName() + " touches " + ref + ", which " + owner + " does not own");
                    }
                }
                if (Pattern.compile("\\b(DROP\\s+(SCHEMA|DATABASE)|CREATE\\s+(SCHEMA|DATABASE|ROLE)|GRANT|REVOKE)\\b", Pattern.CASE_INSENSITIVE).matcher(sql).find()) {
                    problems.add(file.getFileName() + " creates or drops a schema, database or role, or changes grants: that is the platform's, not a service's");
                }
            }
        }
        assertThat(problems).isEmpty();
    }

    @Test
    void platformMigrationsTouchOnlyThePlatformTables() throws IOException {
        Set<String> all = new TreeSet<>();
        schemasByOwner(all);
        List<String> problems = new ArrayList<>();
        for (Path file : sqlFiles(DB.resolve("platform"))) {
            for (String ref : schemaReferences(sqlWithoutComments(file), all)) {
                if (!ref.startsWith("governance.") && !ref.equals("public.event_publication")) {
                    problems.add(file.getFileName() + " touches " + ref + ", which is not a platform table");
                }
            }
        }
        assertThat(problems).isEmpty();
    }

    @Test
    void theImageTheCoresConfigurationAndEachServicesComposeEntryAllListWhatThemMust() throws IOException {
        String dockerfile = Files.readString(Path.of("Dockerfile"), StandardCharsets.UTF_8);
        String application = Files.readString(Path.of("src/main/resources/application.yml"), StandardCharsets.UTF_8);
        String compose = Files.readString(Path.of("../infra/docker/docker-compose.services.yml"), StandardCharsets.UTF_8);
        Set<String> problems = new HashSet<>();

        assertThat(dockerfile).as("the image ships the platform migrations").contains("COPY infra/db/platform /infra/db/platform");
        assertThat(dockerfile).as("the image ships every service's migrations").contains("COPY infra/db/services /infra/db/services");
        assertThat(application).as("the core applies the platform migrations").contains("filesystem:../infra/db/platform");
        assertThat(compose).as("the provisioning job starts a copy at the split baseline").contains("SPLIT_BASELINE: \"" + SPLIT_BASELINE + "\"");
        for (Path folder : serviceFolders()) {
            String name = folder.getFileName().toString();
            if (!application.contains("filesystem:../infra/db/services/" + name)) {
                problems.add("application.yml does not list services/" + name + ", so the core would not apply it to the shared database");
            }
            if (!compose.contains("SPRING_FLYWAY_LOCATIONS: filesystem:../infra/db/platform,filesystem:../infra/db/services/" + name)) {
                problems.add("the " + name + " service's compose entry does not migrate its own database from platform/ and services/" + name);
            }
            if (!compose.contains("../db/services/" + name + ":/service-migrations:ro")) {
                problems.add("the provisioning job does not mount services/" + name + " to baseline the copy");
            }
        }
        assertThat(problems).isEmpty();
    }
}
