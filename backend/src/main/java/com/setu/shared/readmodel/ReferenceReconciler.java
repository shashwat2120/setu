package com.setu.shared.readmodel;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * What a foreign key used to guarantee, checked after the fact. A service that no longer has a constraint to another service's table keeps the
 * other service's id as a plain column, validated when it is written (against its read model) and re-checked here: every row whose reference
 * has no counterpart in the local copy is counted, exposed as {@code setu.reconciliation.orphans{service,reference}} and logged.
 * <p>
 * A non-zero count is information for a person, not something the service repairs on its own: deleting or "fixing" data on a guess would be
 * worse than the inconsistency. See docs/contracts/data-ownership.md section 4.
 */
public class ReferenceReconciler {

    private static final Logger log = LoggerFactory.getLogger(ReferenceReconciler.class);

    /** One reference: rows of {@code table} whose {@code column} is not an id in {@code readModel}. */
    record Reference(String table, String column, String readModel, String key) {
        String name() {
            return table + "." + column;
        }
    }

    /** The references each extracted service holds, by schema. */
    static final Map<String, List<Reference>> REFERENCES = Map.of(
        "learning", List.of(
            new Reference("course_modules", "section_id", "rm_sections", "section_id"),
            new Reference("assignments", "section_id", "rm_sections", "section_id"),
            new Reference("assignment_submissions", "student_id", "rm_students", "student_id"),
            new Reference("learning_progress", "student_id", "rm_students", "student_id")),
        "assessment", List.of(
            new Reference("assessments", "section_id", "rm_sections", "section_id"),
            new Reference("assessment_attempts", "student_id", "rm_students", "student_id"),
            new Reference("grades", "enrollment_id", "rm_enrollments", "enrollment_id")),
        "attendance", List.of(
            new Reference("attendance_sessions", "section_id", "rm_sections", "section_id"),
            new Reference("attendance_sessions", "meeting_id", "rm_meetings", "meeting_id"),
            new Reference("attendance_records", "enrollment_id", "rm_enrollments", "enrollment_id"),
            new Reference("faculty_attendance", "faculty_id", "rm_faculty", "faculty_id"),
            new Reference("faculty_attendance_regularisations", "faculty_id", "rm_faculty", "faculty_id")));

    private final JdbcClient jdbc;
    private final String schema;
    private final MeterRegistry meters;
    private final List<Reference> references;
    private final Map<String, AtomicLong> gauges = new ConcurrentHashMap<>();

    public ReferenceReconciler(JdbcClient jdbc, String schema, MeterRegistry meters) {
        this(jdbc, schema, meters, REFERENCES.getOrDefault(schema, List.of()));
    }

    ReferenceReconciler(JdbcClient jdbc, String schema, MeterRegistry meters, List<Reference> references) {
        this.jdbc = jdbc;
        this.schema = schema;
        this.meters = meters;
        this.references = references;
    }

    /** @return orphan count per reference ("table.column"), empty when this schema holds no references to check */
    public Map<String, Long> reconcile() {
        Map<String, Long> orphans = new LinkedHashMap<>();
        for (Reference reference : references) {
            String table = ReadModelSchema.table(schema, reference.table());
            String copy = ReadModelSchema.table(schema, reference.readModel());
            long count = jdbc.sql("SELECT count(*) FROM " + table + " t WHERE t." + reference.column() + " IS NOT NULL AND NOT EXISTS ("
                + "SELECT 1 FROM " + copy + " c WHERE c." + reference.key() + " = t." + reference.column() + ")").query(Long.class).single();
            orphans.put(reference.name(), count);
            gauges.computeIfAbsent(reference.name(), name -> {
                AtomicLong holder = new AtomicLong();
                meters.gauge("setu.reconciliation.orphans", List.of(io.micrometer.core.instrument.Tag.of("service", schema),
                    io.micrometer.core.instrument.Tag.of("reference", name)), holder);
                return holder;
            }).set(count);
            if (count > 0) {
                log.warn("{} rows of {}.{} reference ids unknown to the local copy of their owner's data", count, schema, reference.name());
            }
        }
        return orphans;
    }

    @Scheduled(initialDelayString = "${app.readmodel.reconcile.initial-delay-ms:120000}", fixedDelayString = "${app.readmodel.reconcile.interval-ms:600000}")
    void scheduled() {
        try {
            reconcile();
        } catch (RuntimeException e) {
            log.warn("reference reconciliation failed: {}", e.toString());
        }
    }
}
