package com.setu.audit;

import com.setu.testsupport.EventSchemas;
import com.setu.testsupport.EventSchemas.Schema;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The shape of every event is a contract with consumers that may be in another service and deployed on another day, so it cannot change by
 * accident. {@code event-schemas.txt} freezes, for every event, its type, payload version, producer, topic and payload fields.
 * <ul>
 *   <li>A new event, or an added field, is a deliberate step: regenerate with {@code -Dfreeze.update=true} and review the diff.</li>
 *   <li>A <b>breaking</b> change (a field removed, or its type changed) is allowed only together with a higher {@code event_version}; the
 *       regeneration refuses otherwise. Consumers refuse events newer than they understand (EventVersions), so a bump never silently
 *       corrupts a consumer that has not been upgraded: it dead-letters the event until it has.</li>
 *   <li>An event type has exactly one class, its producer is the module the class lives in, and its topic is the aggregate in snake case.</li>
 * </ul>
 * Regeneration also rewrites {@code docs/event-schemas.md}, the readable form of the same facts.
 */
class EventSchemaFreezeTests {

    private static final Path FROZEN = Path.of("src/test/resources/event-schemas.txt");
    private static final Path DOC = Path.of("../docs/event-schemas.md");

    private static boolean update() {
        return System.getProperty("freeze.update") != null;
    }

    private static List<String> frozenLines() throws IOException {
        if (!Files.exists(FROZEN)) {
            return List.of();   // the first generation
        }
        return Files.readAllLines(FROZEN, StandardCharsets.UTF_8).stream().filter(l -> !l.isBlank() && !l.startsWith("#")).toList();
    }

    private static Map<String, String[]> parse(List<String> lines) {
        Map<String, String[]> byType = new HashMap<>();
        for (String line : lines) {
            String[] parts = line.split("\t", -1);
            byType.put(parts[0], parts);
        }
        return byType;
    }

    private static Map<String, String> fieldsOf(String encoded) {
        Map<String, String> fields = new TreeMap<>();
        if (!encoded.isBlank()) {
            for (String f : encoded.split(";")) {
                int colon = f.indexOf(':');
                fields.put(f.substring(0, colon), f.substring(colon + 1));
            }
        }
        return fields;
    }

    @Test
    void everyEventTypeHasOneClassWhoseProducerIsItsModuleAndWhoseTopicIsItsAggregate() {
        List<Schema> all = EventSchemas.all();
        assertThat(all.size()).as("the platform publishes events").isGreaterThan(40);
        assertThat(all.stream().map(Schema::eventType).collect(Collectors.groupingBy(t -> t, Collectors.counting())))
            .as("event types claimed by more than one class").allSatisfy((type, count) -> assertThat(count).as(type).isEqualTo(1L));
        for (Schema s : all) {
            String module = s.eventClass().getPackageName().split("\\.")[2];
            assertThat(s.producer()).as("%s: producer is the module that owns the class", s.eventType()).isEqualTo(module);
            assertThat(s.aggregateType()).as("%s: topic name", s.eventType()).matches("[a-z][a-z0-9_]*");
            assertThat(s.version()).as("%s: version", s.eventType()).isGreaterThanOrEqualTo(1);
            assertThat(s.fields()).as("%s: carries the envelope ids in its payload", s.eventType()).containsKeys("eventId", "occurredAt");
        }
    }

    @Test
    void theEventsMatchTheFrozenSchemasAndNoChangeIsBreakingWithoutAVersionBump() throws IOException {
        List<Schema> actual = EventSchemas.all();
        Map<String, String[]> frozen = parse(frozenLines());

        List<String> breakingWithoutBump = new ArrayList<>();
        for (Schema s : actual) {
            String[] old = frozen.get(s.eventType());
            if (old == null) {
                continue;
            }
            Map<String, String> before = fieldsOf(old[5]);
            Map<String, String> now = new TreeMap<>(s.fields());
            boolean breaking = before.entrySet().stream().anyMatch(e -> !e.getValue().equals(now.get(e.getKey())));
            if (breaking && s.version() <= Integer.parseInt(old[1])) {
                breakingWithoutBump.add(s.eventType() + ": a field was removed or changed type, but event_version is still " + s.version());
            }
            if (s.version() < Integer.parseInt(old[1])) {
                breakingWithoutBump.add(s.eventType() + ": event_version went down from " + old[1] + " to " + s.version());
            }
        }
        assertThat(breakingWithoutBump).as("breaking changes need a higher event_version").isEmpty();

        if (update()) {
            StringBuilder out = new StringBuilder("# Frozen event schemas. Generated by EventSchemaFreezeTests (-Dfreeze.update=true); see docs/contracts/kafka-contracts.md.\n"
                + "# eventType, payload version, producer, topic (aggregate), class, payload fields (name:Type)\n");
            actual.forEach(s -> out.append(s.line()).append('\n'));
            Files.writeString(FROZEN, out.toString(), StandardCharsets.UTF_8);
            Files.writeString(DOC, document(actual), StandardCharsets.UTF_8);
            return;
        }

        List<String> actualLines = actual.stream().map(Schema::line).toList();
        List<String> added = new ArrayList<>(actualLines);
        added.removeAll(frozenLines());
        List<String> removed = new ArrayList<>(frozenLines());
        removed.removeAll(actualLines);
        assertThat(added).as("events or fields added or changed since the freeze: regenerate with -Dfreeze.update=true and review").isEmpty();
        assertThat(removed).as("events or fields that no longer exist: regenerate with -Dfreeze.update=true and review").isEmpty();
    }

    @Test
    void theGeneratedDocumentIsUpToDate() throws IOException {
        if (update()) {
            return;
        }
        assertThat(Files.readString(DOC, StandardCharsets.UTF_8).replace("\r\n", "\n"))
            .as("docs/event-schemas.md is generated from the event classes: regenerate with -Dfreeze.update=true")
            .isEqualTo(document(EventSchemas.all()));
    }

    static String document(List<Schema> schemas) {
        StringBuilder md = new StringBuilder();
        md.append("# Event schemas\n\n")
            .append("Generated from the event classes by `EventSchemaFreezeTests` (`-Dfreeze.update=true`); do not edit by hand. Every event has the canonical\n")
            .append("envelope in its Kafka headers (see [contracts/kafka-contracts.md](contracts/kafka-contracts.md)); this lists what varies per event.\n")
            .append("`Topic` is `setu.<aggregate>`, and the record key is the aggregate id, so one aggregate's events stay in order.\n\n");
        Map<String, List<Schema>> byProducer = new TreeMap<>();
        schemas.forEach(s -> byProducer.computeIfAbsent(s.producer(), k -> new ArrayList<>()).add(s));
        md.append("**").append(schemas.size()).append(" events from ").append(byProducer.size()).append(" producers.**\n");
        byProducer.forEach((producer, list) -> {
            md.append("\n## ").append(producer).append("\n\n| Event | Version | Topic | Payload fields |\n|---|---|---|---|\n");
            list.forEach(s -> md.append("| `").append(s.eventType()).append("` | ").append(s.version()).append(" | `setu.").append(s.aggregateType()).append("` | ")
                .append(s.fields().entrySet().stream().filter(e -> !List.of("eventId", "occurredAt", "tenantId", "aggregateId", "correlationId", "causationId")
                    .contains(e.getKey())).map(e -> "`" + e.getKey() + "` " + e.getValue()).collect(Collectors.joining(", "))).append(" |\n"));
        });
        return md.toString();
    }

    @Test
    void producersAreListedSoAnExtractedServiceKnowsWhatItOwns() {
        assertThat(new TreeSet<>(EventSchemas.all().stream().map(Schema::producer).toList()))
            .contains("identity", "enrollment", "directory", "attendance", "assessment", "guardianaccess", "institution", "catalog");
    }
}
