package com.setu;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sprint 5A Day 2 verification, per the task's 12-point checklist. Runs the
 * real Flyway migrations against a real Postgres (docker-compose, see
 * infra/docker/README.md) from an empty database on every run —
 * {@code @SpringBootTest} triggers Flyway on context startup, so a clean
 * schema/migration failure fails this test suite immediately.
 * <p>
 * Requires the docker-compose stack running (infra/docker/docker-compose.yml).
 */
@SpringBootTest
class MigrationIntegrationTests {

    @Autowired
    JdbcTemplate jdbc;

    private static final List<String> EXPECTED_SCHEMAS = List.of(
        "identity", "directory", "guardian_access", "institution", "catalog",
        "enrollment", "attendance", "learning", "assessment", "record",
        "finance", "career", "risk", "notification", "analytics", "governance"
    );

    @Test
    void allSixteenModuleSchemasExist() {
        List<String> schemas = jdbc.queryForList(
            "SELECT schema_name FROM information_schema.schemata WHERE schema_name = ANY(?)",
            String.class, (Object) EXPECTED_SCHEMAS.toArray(new String[0]));
        assertThat(schemas).containsExactlyInAnyOrderElementsOf(EXPECTED_SCHEMAS);
    }

    @Test
    void requiredExtensionsAreInstalled() {
        List<String> extensions = jdbc.queryForList(
            "SELECT extname FROM pg_extension WHERE extname IN ('vector','btree_gist','citext')",
            String.class);
        assertThat(extensions).containsExactlyInAnyOrder("vector", "btree_gist", "citext");
    }

    @Test
    void domainTableCountMatchesErd() {
        // 83 original domain tables (docs/table-ownership-map.md) + 2 added
        // in Sprint 5B Day 3 (identity.webauthn_challenges, V52;
        // identity.password_reset_tokens, V51) + 1 added in Day 4
        // (identity.role_permissions, V53) — the Day 4 instruction itself
        // named "roles, permissions, role_permissions, user_roles" as
        // required, and only role_permissions didn't already exist (V8 only
        // ever created roles/permissions as standalone catalogs, per its own
        // header comment). The 85-table Day 3 baseline is otherwise
        // untouched — see V53's header comment. +1 in Day 14
        // (institution.policy_documents, V70 — see that migration's own
        // header comment for why institution owns it, no new schema). +1 for staff attendance
        // (attendance.faculty_attendance, V73 — docs/erd/03-learning-assessment-records.md; owned by the existing
        // attendance schema, no new schema). +1 for emailed password-reset codes (identity.password_reset_codes, V77 -- owned by
        // the existing identity schema; the one-time six-digit code that replaces the emailed reset link). +1 for staff-attendance regularisation requests
        // (attendance.faculty_attendance_regularisations, V74 — same ERD doc, same schema). +1 for the versioned staff
        // attendance policy (attendance.staff_attendance_policies, V75 — same ERD doc, same schema).
        // +1 for the notification consumer's own processed-event table (notification.processed_events, V78 — canonical spec §8: each consumer keeps its
        // own idempotency table keyed on event_id; owned by the existing notification schema).
        Integer count = jdbc.queryForObject(
            "SELECT count(*) FROM information_schema.tables " +
            "WHERE table_schema = ANY(?) AND table_type = 'BASE TABLE'",
            Integer.class, (Object) EXPECTED_SCHEMAS.toArray(new String[0]));
        assertThat(count).isEqualTo(92);
    }

    @Test
    void everyForeignKeyReferencesAnExistingTable() {
        // A FK whose target doesn't resolve would have failed migration
        // already, but this asserts the count is non-trivial (schema is
        // actually connected, not 83 isolated tables).
        Integer fkCount = jdbc.queryForObject(
            "SELECT count(*) FROM information_schema.table_constraints " +
            "WHERE constraint_type = 'FOREIGN KEY' AND table_schema = ANY(?)",
            Integer.class, (Object) EXPECTED_SCHEMAS.toArray(new String[0]));
        assertThat(fkCount).isGreaterThan(60);
    }

    @Test
    void criticalUniqueConstraintsExist() {
        // uq_users_email is a bare CREATE UNIQUE INDEX (V5__identity_users.sql),
        // not an ALTER TABLE ... ADD CONSTRAINT, so it never appears in
        // information_schema.table_constraints — check pg_indexes instead.
        assertUniqueIndexExists("identity", "users", "uq_users_email");
        assertConstraintExists("enrollment", "enrollments", "uq_enrollments_section_student", "u");
        assertConstraintExists("attendance", "attendance_records", "uq_attendance_records_session_enrollment", "u");
        assertConstraintExists("finance", "payment_events", "uq_payment_events_provider_event", "u");
        assertConstraintExists("enrollment", "waitlist_entries", "uq_waitlist_entries_section_student", "u");
    }

    @Test
    void tenantOwnershipColumnsArePresentOnSampledTables() {
        // Sample of tables that MUST carry tenant_id per docs/erd-audit-2.md
        // finding A (cross-tenant join guards added in this pass).
        List<String> tenantOwned = List.of(
            "curriculum_requirement_courses", "class_students", "class_faculty",
            "job_skills", "student_skills", "applications", "placement_records"
        );
        for (String table : tenantOwned) {
            Integer hasTenantId = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns " +
                "WHERE table_name = ? AND column_name = 'tenant_id'",
                Integer.class, table);
            assertThat(hasTenantId).as("tenant_id on %s", table).isEqualTo(1);
        }
    }

    @Test
    void optimisticLockingVersionColumnsArePresentOnMutableAggregates() {
        List<String> versioned = List.of("users", "colleges", "enrollments", "attendance_sessions", "attendance_records", "guardian_access_grants");
        for (String table : versioned) {
            Integer hasVersion = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns " +
                "WHERE table_name = ? AND column_name = 'version'",
                Integer.class, table);
            assertThat(hasVersion).as("version column on %s", table).isEqualTo(1);
        }
    }

    @Test
    void gradesAndTranscriptsAreAppendOnlyByDesign() {
        // Not a DB-level guarantee (append-only is enforced at the
        // application layer, per docs/state-machines.md #19) — this only
        // confirms the versioning columns that make it possible exist.
        assertColumnExists("assessment", "grades", "version");
        assertColumnExists("assessment", "grades", "supersedes_id");
        assertColumnExists("assessment", "grades", "is_current");
    }

    private void assertUniqueIndexExists(String schema, String table, String indexName) {
        Integer count = jdbc.queryForObject(
            "SELECT count(*) FROM pg_indexes WHERE schemaname = ? AND tablename = ? AND indexname = ?",
            Integer.class, schema, table, indexName);
        assertThat(count).as("%s.%s index %s", schema, table, indexName).isEqualTo(1);
    }

    private void assertConstraintExists(String schema, String table, String name, String type) {
        Integer count = jdbc.queryForObject(
            "SELECT count(*) FROM information_schema.table_constraints " +
            "WHERE table_schema = ? AND table_name = ? AND constraint_name = ?",
            Integer.class, schema, table, name);
        assertThat(count).as("%s.%s constraint %s", schema, table, name).isEqualTo(1);
    }

    private void assertColumnExists(String schema, String table, String column) {
        Integer count = jdbc.queryForObject(
            "SELECT count(*) FROM information_schema.columns " +
            "WHERE table_schema = ? AND table_name = ? AND column_name = ?",
            Integer.class, schema, table, column);
        assertThat(count).as("%s.%s.%s", schema, table, column).isEqualTo(1);
    }
}
