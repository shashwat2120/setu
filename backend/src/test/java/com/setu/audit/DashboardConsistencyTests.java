package com.setu.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The dashboards and the scrape configuration are checked against the code, because a mismatch is silent: a panel naming a meter nobody produces
 * just stays empty, and a service that Prometheus is not pointed at just never shows up (docs/contracts/observability.md). Both fail here instead.
 */
class DashboardConsistencyTests {

    private static final Path DASHBOARDS = Path.of("../infra/docker/observability/grafana/dashboards");
    private static final Path PROMETHEUS = Path.of("../infra/docker/observability/prometheus.yml");
    private static final Path MAIN_SOURCES = Path.of("src/main/java");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Set<String> normalised(Stream<String> names) {
        return names.map(n -> n.replace('.', '_')).collect(Collectors.toCollection(TreeSet::new));
    }

    /** Every {@code "setu.some.meter"} string literal in the code, with dots turned into underscores the way Prometheus renders them. */
    private static Set<String> meterNamesInCode() throws IOException {
        Pattern literal = Pattern.compile("\"(setu\\.[a-z_.]+)\"");
        try (Stream<Path> files = Files.walk(MAIN_SOURCES)) {
            return normalised(files.filter(p -> p.toString().endsWith(".java")).flatMap(p -> {
                try {
                    Matcher m = literal.matcher(Files.readString(p, StandardCharsets.UTF_8));
                    Set<String> found = new TreeSet<>();
                    while (m.find()) {
                        found.add(m.group(1));
                    }
                    return found.stream();
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            }));
        }
    }

    /** What Prometheus appends to a meter's name (counter, timer, summary, gauge units); removed to get back to the name the code uses. */
    private static String baseName(String prometheusName) {
        return prometheusName.replaceAll("(_total|_seconds_count|_seconds_sum|_seconds_bucket|_seconds_max|_seconds|_bytes|_count|_sum|_bucket|_max)$", "");
    }

    private static Set<String> setuMetricsUsedBy(Path dashboard) throws IOException {
        JsonNode json = MAPPER.readTree(Files.readString(dashboard, StandardCharsets.UTF_8));
        Set<String> used = new TreeSet<>();
        Pattern identifier = Pattern.compile("\\bsetu_[a-z0-9_]+");
        for (JsonNode panel : json.get("panels")) {
            for (JsonNode target : panel.path("targets")) {
                Matcher m = identifier.matcher(target.path("expr").asText(""));
                while (m.find()) {
                    used.add(m.group());
                }
            }
        }
        return used;
    }

    @Test
    void everyMeterADashboardNamesIsOneTheCodeProduces() throws IOException {
        Set<String> inCode = meterNamesInCode();
        assertThat(inCode).as("the scan finds the meters").contains("setu_gateway_requests", "setu_outbox_pending");
        Map<String, Set<String>> missing = new TreeMap<>();
        int dashboards = 0;
        try (Stream<Path> files = Files.list(DASHBOARDS)) {
            for (Path dashboard : files.filter(p -> p.toString().endsWith(".json")).toList()) {
                dashboards++;
                Set<String> unknown = setuMetricsUsedBy(dashboard).stream().filter(name -> !inCode.contains(baseName(name)))
                    .collect(Collectors.toCollection(TreeSet::new));
                if (!unknown.isEmpty()) {
                    missing.put(dashboard.getFileName().toString(), unknown);
                }
            }
        }
        assertThat(dashboards).as("dashboards found").isGreaterThanOrEqualTo(2);
        assertThat(missing).as("panels that name a meter no code produces (they would stay empty)").isEmpty();
    }

    @Test
    void everyDashboardIsValidJsonWithUniquePanelIds() throws IOException {
        try (Stream<Path> files = Files.list(DASHBOARDS)) {
            for (Path dashboard : files.filter(p -> p.toString().endsWith(".json")).toList()) {
                JsonNode json = MAPPER.readTree(Files.readString(dashboard, StandardCharsets.UTF_8));
                assertThat(json.path("uid").asText()).as(dashboard + " uid").isNotBlank();
                Set<Integer> ids = new TreeSet<>();
                for (JsonNode panel : json.get("panels")) {
                    assertThat(ids.add(panel.get("id").asInt())).as(dashboard + " duplicate panel id " + panel.get("id")).isTrue();
                }
            }
        }
    }

    @Test
    void prometheusScrapesEveryServiceOnThePortItsEntryPointListensOn() throws IOException {
        // (service name -> port) from the entry points' defaults
        Pattern service = Pattern.compile("\"setu\\.service\",\\s*\"([a-z]+)\"");
        Pattern port = Pattern.compile("\"SERVER_PORT\",\\s*\"(\\d+)\"");
        Map<String, String> listening = new TreeMap<>();
        try (Stream<Path> files = Files.walk(MAIN_SOURCES)) {
            for (Path file : files.filter(p -> p.getFileName().toString().endsWith("Application.java")).toList()) {
                String text = Files.readString(file, StandardCharsets.UTF_8);
                Matcher s = service.matcher(text);
                Matcher p = port.matcher(text);
                if (s.find() && p.find()) {
                    listening.put(s.group(1), p.group(1));
                }
            }
        }
        assertThat(listening.keySet()).as("entry points found").contains("identity", "academic", "learning", "assessment", "attendance", "notification", "ai", "gateway");
        // the config server is not part of the request path and exposes no business metrics; everything else is scraped
        listening.keySet().retainAll(Set.of("identity", "academic", "learning", "assessment", "attendance", "notification", "ai", "gateway"));

        String config = Files.readString(PROMETHEUS, StandardCharsets.UTF_8);
        Pattern target = Pattern.compile("targets:\\s*\\['(?:host\\.docker\\.internal|[a-z]+):(\\d+)'\\]\\s*labels:\\s*\\{\\s*service:\\s*([a-z-]+)\\s*}");
        Matcher t = target.matcher(config);
        Map<String, String> scraped = new TreeMap<>();
        while (t.find()) {
            scraped.put(t.group(2), t.group(1));
        }
        assertThat(scraped).as("what Prometheus scrapes, by service").containsAllEntriesOf(listening);
    }
}
