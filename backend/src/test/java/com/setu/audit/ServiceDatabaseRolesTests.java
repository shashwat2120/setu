package com.setu.audit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Table ownership enforced by the database and not only by convention: each service logs in as its own database role (infra/db/roles/service-roles.sql) and a role can use
 * only the schemas that service owns. These tests connect as each role and try what a service must not be able to do: read another service's tables (the identity
 * service's accounts and sessions, a payment), change or remove an audit record, change the schema. The refusals are PostgreSQL's own ("permission denied", SQLSTATE 42501), so
 * a service that reaches into another's data fails loudly instead of quietly working.
 */
@SpringBootTest
class ServiceDatabaseRolesTests {

    private static final String PERMISSION_DENIED = "42501";
    private static final String PASSWORD = "setu_dev_password";

    /** The role each owner named in table-owners.txt logs in as. */
    private static final Map<String, String> ROLE_OF_OWNER = Map.of(
        "Identity", "setu_identity", "Academic", "setu_academic", "Learning", "setu_learning",
        "Assessment", "setu_assessment", "Attendance", "setu_attendance", "Notification", "setu_notification");

    /** Owned on paper, but still run by the core, so granted to no service role (Assessment owns the record schema until Record is extracted). */
    private static final Set<String> CORE_ONLY = Set.of("record", "finance", "career", "risk");

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    Environment env;

    /** schema -> owner, and one table of each schema to try. */
    private final Map<String, String> ownerOfSchema = new LinkedHashMap<>();
    private final Map<String, String> tableOfSchema = new LinkedHashMap<>();

    @BeforeEach
    void applyTheRolesAndReadTheOwnership() throws IOException {
        runRolesScript();
        for (String line : Files.readAllLines(Path.of("src/test/resources/table-owners.txt"), StandardCharsets.UTF_8)) {
            String l = line.strip();
            if (l.isEmpty() || l.startsWith("#")) {
                continue;
            }
            String[] parts = l.split("\\s+");
            String schema = parts[0].substring(0, parts[0].indexOf('.'));
            ownerOfSchema.putIfAbsent(schema, parts[1]);
            tableOfSchema.putIfAbsent(schema, parts[0]);
        }
    }

    @AfterEach
    void removeProbes() throws IOException {
        jdbc.execute("DROP TABLE IF EXISTS learning.zz_roles_probe");
        runRolesScript(); // takes back any grant a test added
    }

    private void runRolesScript() throws IOException {
        jdbc.execute(Files.readString(Path.of("../infra/db/roles/service-roles.sql"), StandardCharsets.UTF_8));
    }

    private Connection connectAs(String role) throws SQLException {
        Connection c = DriverManager.getConnection(env.getProperty("spring.datasource.url"), role, PASSWORD);
        c.setAutoCommit(true);
        return c;
    }

    /** The SQLSTATE of the failure, or null if the statement ran. */
    private String attempt(String role, String sql) throws SQLException {
        try (Connection c = connectAs(role); Statement s = c.createStatement()) {
            s.execute(sql);
            return null;
        } catch (SQLException e) {
            return e.getSQLState();
        }
    }

    private Set<String> schemasOf(String owner) {
        Set<String> out = new TreeSet<>();
        ownerOfSchema.forEach((schema, o) -> {
            if (o.equals(owner) && !CORE_ONLY.contains(schema)) {
                out.add(schema);
            }
        });
        return out;
    }

    @Test
    void eachServiceReadsItsOwnSchemasAndNoOtherServicesAndNotTheCoreOnes() throws SQLException {
        List<String> wrong = new ArrayList<>();
        for (var entry : ROLE_OF_OWNER.entrySet()) {
            Set<String> mine = schemasOf(entry.getKey());
            assertThat(mine).as(entry.getKey() + " owns something").isNotEmpty();
            for (var schema : tableOfSchema.entrySet()) {
                if (schema.getKey().equals("governance")) {
                    continue;
                }
                String outcome = attempt(entry.getValue(), "SELECT 1 FROM " + schema.getValue() + " LIMIT 1");
                boolean allowed = mine.contains(schema.getKey());
                if (allowed && outcome != null) {
                    wrong.add(entry.getValue() + " should read " + schema.getValue() + " but got " + outcome);
                }
                if (!allowed && !PERMISSION_DENIED.equals(outcome)) {
                    wrong.add(entry.getValue() + " should be refused " + schema.getValue() + " but got " + outcome);
                }
            }
        }
        assertThat(wrong).isEmpty();
    }

    @Test
    void theHeadlineCases() throws SQLException {
        assertThat(attempt("setu_learning", "SELECT email FROM identity.users LIMIT 1")).as("learning reading everyone's email addresses").isEqualTo(PERMISSION_DENIED);
        assertThat(attempt("setu_notification", "SELECT * FROM finance.payments LIMIT 1")).as("notification reading payments").isEqualTo(PERMISSION_DENIED);
        assertThat(attempt("setu_attendance", "SELECT * FROM assessment.grades LIMIT 1")).as("attendance reading grades").isEqualTo(PERMISSION_DENIED);
        assertThat(attempt("setu_assessment", "SELECT * FROM record.transcripts LIMIT 1")).as("assessment reading the core's record schema").isEqualTo(PERMISSION_DENIED);
        assertThat(attempt("setu_identity", "SELECT 1 FROM identity.users LIMIT 1")).as("identity reading its own users").isNull();
    }

    @Test
    void aServiceCannotWriteToAnotherServicesTablesEither() throws SQLException {
        assertThat(attempt("setu_learning", "UPDATE identity.users SET status = status WHERE false")).isEqualTo(PERMISSION_DENIED);
        assertThat(attempt("setu_learning", "DELETE FROM enrollment.enrollments WHERE false")).isEqualTo(PERMISSION_DENIED);
        assertThat(attempt("setu_learning", "INSERT INTO notification.notifications SELECT * FROM notification.notifications WHERE false")).isEqualTo(PERMISSION_DENIED);
    }

    @Test
    void theCrossCuttingTablesAreOpenToEveryServiceOnlyForWhatEachIsFor() throws SQLException {
        for (String role : ROLE_OF_OWNER.values()) {
            assertThat(attempt(role, "SELECT 1 FROM governance.outbox_events LIMIT 1")).as(role + " reads the outbox").isNull();
            assertThat(attempt(role, "UPDATE governance.outbox_events SET status = status WHERE false")).as(role + " marks events published").isNull();
            assertThat(attempt(role, "DELETE FROM governance.outbox_events WHERE false")).as(role + " must not delete outbox events").isEqualTo(PERMISSION_DENIED);
            assertThat(attempt(role, "SELECT 1 FROM governance.audit_logs LIMIT 1")).as(role + " reads the audit log").isNull();
            assertThat(attempt(role, "UPDATE governance.audit_logs SET action = action WHERE false")).as(role + " must not change an audit record").isEqualTo(PERMISSION_DENIED);
            assertThat(attempt(role, "DELETE FROM governance.audit_logs WHERE false")).as(role + " must not remove an audit record").isEqualTo(PERMISSION_DENIED);
            assertThat(attempt(role, "SELECT 1 FROM governance.idempotency_keys LIMIT 1")).as(role + " uses idempotency keys").isNull();
        }
    }

    @Test
    void theFrameworksEventLogIsOpenToEveryServiceAndTheMigrationHistoryToNone() throws SQLException {
        for (String role : ROLE_OF_OWNER.values()) {
            assertThat(attempt(role, "SELECT 1 FROM public.event_publication LIMIT 1")).as(role + " reads the framework's event log").isNull();
            assertThat(attempt(role, "INSERT INTO public.event_publication SELECT * FROM public.event_publication WHERE false")).as(role + " adds to it").isNull();
            assertThat(attempt(role, "UPDATE public.event_publication SET status = status WHERE false")).as(role + " completes a publication").isNull();
            assertThat(attempt(role, "SELECT 1 FROM public.flyway_schema_history LIMIT 1")).as(role + " must not read the migration history").isEqualTo(PERMISSION_DENIED);
            assertThat(attempt(role, "DELETE FROM public.flyway_schema_history WHERE false")).as(role + " must not touch the migration history").isEqualTo(PERMISSION_DENIED);
        }
    }

    @Test
    void noServiceCanChangeTheSchema() throws SQLException {
        for (String role : ROLE_OF_OWNER.values()) {
            assertThat(attempt(role, "CREATE TABLE public.zz_roles_probe (id int)")).as(role + " creating in public").isEqualTo(PERMISSION_DENIED);
            assertThat(attempt(role, "CREATE SCHEMA zz_roles_probe")).as(role + " creating a schema").isEqualTo(PERMISSION_DENIED);
        }
        assertThat(attempt("setu_learning", "CREATE TABLE learning.zz_roles_probe (id int)")).as("creating in its own schema").isEqualTo(PERMISSION_DENIED);
        assertThat(attempt("setu_learning", "ALTER TABLE learning.lessons ADD COLUMN zz_probe int")).as("altering its own table").isEqualTo(PERMISSION_DENIED);
        assertThat(attempt("setu_learning", "DROP TABLE learning.lessons")).as("dropping its own table").isEqualTo(PERMISSION_DENIED);
        assertThat(attempt("setu_learning", "TRUNCATE learning.lessons")).as("truncating its own table").isEqualTo(PERMISSION_DENIED);
    }

    @Test
    void aTableAddedLaterByAMigrationIsCoveredForItsOwnerAndStillRefusedToTheOthers() throws SQLException {
        jdbc.execute("CREATE TABLE learning.zz_roles_probe (id int)");

        assertThat(attempt("setu_learning", "SELECT 1 FROM learning.zz_roles_probe")).as("the owner of the schema").isNull();
        assertThat(attempt("setu_notification", "SELECT 1 FROM learning.zz_roles_probe")).as("another service").isEqualTo(PERMISSION_DENIED);
    }

    @Test
    void aGrantThatIsNotInTheScriptIsTakenBackTheNextTimeItRuns() throws SQLException, IOException {
        jdbc.execute("GRANT USAGE ON SCHEMA identity TO setu_learning");
        jdbc.execute("GRANT SELECT ON identity.permissions TO setu_learning");
        assertThat(attempt("setu_learning", "SELECT 1 FROM identity.permissions LIMIT 1")).as("while the stray grant is there").isNull();

        runRolesScript();

        assertThat(attempt("setu_learning", "SELECT 1 FROM identity.permissions LIMIT 1")).as("after the script ran again").isEqualTo(PERMISSION_DENIED);
    }

    @Test
    void theScriptAndTableOwnersTxtAgreeOnWhoHoldsWhat() {
        for (var entry : ROLE_OF_OWNER.entrySet()) {
            Set<String> expected = schemasOf(entry.getKey());
            expected.add("governance");
            Set<String> held = new TreeSet<>(jdbc.queryForList("""
                SELECT nspname FROM pg_namespace
                WHERE nspname NOT LIKE 'pg\\_%' AND nspname NOT IN ('information_schema', 'public')
                  AND has_schema_privilege(?, oid, 'USAGE')
                """, String.class, entry.getValue()));
            assertThat(held).as(entry.getValue() + " may use exactly the schemas " + entry.getKey() + " owns, plus governance").isEqualTo(expected);
        }
    }

    @Test
    void everyServiceRoleExistsCannotCreateRolesOrDatabasesAndIsNotASuperuser() {
        for (String role : ROLE_OF_OWNER.values()) {
            var row = jdbc.queryForMap("SELECT rolcanlogin, rolsuper, rolcreatedb, rolcreaterole FROM pg_roles WHERE rolname = ?", role);
            assertThat(row).as(role).containsEntry("rolcanlogin", true).containsEntry("rolsuper", false).containsEntry("rolcreatedb", false).containsEntry("rolcreaterole", false);
        }
    }
}
