package com.setu.shared.readmodel;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The after-the-fact check that replaces the dropped foreign keys: an id with no counterpart in the local copy of its owner's data is
 * counted, reported per reference and exposed as a metric, a null reference is not an orphan, and nothing is changed. Runs against a scratch
 * schema so it does not depend on what the real service tables happen to contain.
 */
@SpringBootTest
class ReferenceReconcilerTests {

    private static final String SCHEMA = "recon_scratch";

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void createScratchTables() {
        jdbc.sql("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE").update();
        jdbc.sql("CREATE SCHEMA " + SCHEMA).update();
        jdbc.sql("CREATE TABLE " + SCHEMA + ".rm_sections (section_id UUID PRIMARY KEY)").update();
        jdbc.sql("CREATE TABLE " + SCHEMA + ".rm_students (student_id UUID PRIMARY KEY)").update();
        jdbc.sql("CREATE TABLE " + SCHEMA + ".course_modules (id UUID PRIMARY KEY, section_id UUID)").update();
        jdbc.sql("CREATE TABLE " + SCHEMA + ".learning_progress (id UUID PRIMARY KEY, student_id UUID)").update();
    }

    @AfterEach
    void dropScratchTables() {
        jdbc.sql("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE").update();
    }

    private void insert(String table, String column, UUID value) {
        jdbc.sql("INSERT INTO " + SCHEMA + "." + table + " (id, " + column + ") VALUES (:id, :v)").param("id", UUID.randomUUID()).param("v", value).update();
    }

    private ReferenceReconciler reconciler(SimpleMeterRegistry meters) {
        return new ReferenceReconciler(jdbc, SCHEMA, meters, List.of(
            new ReferenceReconciler.Reference("course_modules", "section_id", "rm_sections", "section_id"),
            new ReferenceReconciler.Reference("learning_progress", "student_id", "rm_students", "student_id")));
    }

    @Test
    void anIdTheLocalCopyDoesNotKnowIsCountedAndAKnownOrNullOneIsNot() {
        UUID known = UUID.randomUUID();
        jdbc.sql("INSERT INTO " + SCHEMA + ".rm_sections (section_id) VALUES (:id)").param("id", known).update();
        insert("course_modules", "section_id", known);
        insert("course_modules", "section_id", null);
        insert("course_modules", "section_id", UUID.randomUUID());
        insert("course_modules", "section_id", UUID.randomUUID());

        var meters = new SimpleMeterRegistry();
        Map<String, Long> orphans = reconciler(meters).reconcile();

        assertThat(orphans).containsEntry("course_modules.section_id", 2L).containsEntry("learning_progress.student_id", 0L);
        assertThat(meters.get("setu.reconciliation.orphans").tag("reference", "course_modules.section_id").gauge().value()).isEqualTo(2.0);
        assertThat(jdbc.sql("SELECT count(*) FROM " + SCHEMA + ".course_modules").query(Long.class).single()).as("nothing is repaired or deleted").isEqualTo(4L);
    }

    @Test
    void theGaugeFollowsTheDataWhenTheOrphanIsResolved() {
        UUID student = UUID.randomUUID();
        insert("learning_progress", "student_id", student);
        var meters = new SimpleMeterRegistry();
        var reconciler = reconciler(meters);

        assertThat(reconciler.reconcile()).containsEntry("learning_progress.student_id", 1L);
        jdbc.sql("INSERT INTO " + SCHEMA + ".rm_students (student_id) VALUES (:id)").param("id", student).update();
        assertThat(reconciler.reconcile()).containsEntry("learning_progress.student_id", 0L);
        assertThat(meters.get("setu.reconciliation.orphans").tag("reference", "learning_progress.student_id").gauge().value()).isZero();
    }

    @Test
    void everyReferenceTheServicesHoldNamesRealColumnsAndRealCopies() {
        // runs the real check lists against the real tables: a typo in a table or column name fails here, not in production at 3 a.m.
        for (String schema : ReferenceReconciler.REFERENCES.keySet()) {
            assertThat(new ReferenceReconciler(jdbc, schema, new SimpleMeterRegistry()).reconcile())
                .as(schema).hasSameSizeAs(ReferenceReconciler.REFERENCES.get(schema));
        }
    }

    @Test
    void aSchemaWithNoReferencesChecksNothing() {
        assertThat(new ReferenceReconciler(jdbc, "institution", new SimpleMeterRegistry()).reconcile()).isEmpty();
    }
}
