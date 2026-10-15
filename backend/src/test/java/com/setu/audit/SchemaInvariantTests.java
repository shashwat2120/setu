package com.setu.audit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The non-negotiables that are really statements about the data model (canonical spec §3 and §5), checked against the live schema so a
 * future migration cannot quietly break them: attendance percentage is derived not stored, binary media stays out of PostgreSQL, an
 * account is never mixed into a profile, and every row belongs to a tenant, directly or through its parent.
 */
@SpringBootTest
class SchemaInvariantTests {

    private static final String DOMAIN_SCHEMAS =
        "'identity','institution','directory','guardian_access','catalog','enrollment','attendance','learning','assessment','record',"
            + "'finance','career','risk','notification','governance'";

    @Autowired
    JdbcClient jdbc;

    @Test
    void attendancePercentageIsDerivedNeverStored() {
        List<String> columns = jdbc.sql("""
                SELECT table_name || '.' || column_name FROM information_schema.columns
                WHERE table_schema = 'attendance'
                  AND (column_name ILIKE '%percent%' OR column_name ILIKE '%pct%' OR column_name ILIKE '%rate%' OR column_name ILIKE '%ratio%')
                """).query(String.class).list();

        assertThat(columns).as("a stored attendance percentage would drift from the records it is meant to summarise").isEmpty();
    }

    @Test
    void binaryMediaStaysOutsidePostgres() {
        // The only binary columns are WebAuthn cryptographic material, which is small and cannot live elsewhere.
        Set<String> binary = new TreeSet<>(jdbc.sql("""
                SELECT table_schema || '.' || table_name || '.' || column_name FROM information_schema.columns
                WHERE data_type = 'bytea' AND table_schema IN (%s)
                """.formatted(DOMAIN_SCHEMAS)).query(String.class).list());

        assertThat(binary).containsExactlyInAnyOrder("identity.passkeys.credential_id", "identity.passkeys.public_key",
            "identity.webauthn_challenges.challenge");
        assertThat(jdbc.sql("SELECT count(*) FROM pg_largeobject_metadata").query(Integer.class).single())
            .as("no PostgreSQL large objects").isZero();
    }

    @Test
    void paymentTablesNeverHoldCardNumbers() {
        List<String> columns = jdbc.sql("""
                SELECT table_name || '.' || column_name FROM information_schema.columns
                WHERE table_schema = 'finance'
                  AND (column_name ILIKE '%pan%' OR column_name ILIKE '%cvv%' OR column_name ILIKE '%card%' OR column_name ILIKE '%expiry%')
                  AND column_name NOT ILIKE '%company%' AND column_name NOT ILIKE '%expan%'
                """).query(String.class).list();

        assertThat(columns).as("PAN/CVV must never be stored: only the provider's opaque token").isEmpty();
    }

    @Test
    void anAccountIsSeparateFromEveryProfile() {
        Map<String, String> profileColumns = columnsByTable("directory.student_profiles", "directory.faculty_profiles", "directory.guardians");
        for (var entry : profileColumns.entrySet()) {
            assertThat(entry.getValue())
                .as(entry.getKey() + " must hold no credentials and no direct link to the login account")
                .doesNotContainIgnoringCase("password").doesNotContainIgnoringCase("passkey").doesNotContainIgnoringCase("credential")
                .doesNotContainIgnoringCase("session").doesNotContainIgnoringCase("user_id").doesNotContainIgnoringCase("email");
            assertThat(entry.getValue()).as(entry.getKey() + " links to a person, who links to an account").contains("person_id");
        }
        String users = columnsByTable("identity.users").get("identity.users");
        assertThat(users).as("the account holds no profile data")
            .doesNotContain("admission_no").doesNotContain("roll_no").doesNotContain("employee_no").doesNotContain("legal_name")
            .doesNotContain("dob").doesNotContain("department_id").doesNotContain("program_id");
        assertThat(columnsByTable("identity.persons").get("identity.persons")).contains("user_id");
    }

    @Test
    void everyTenantIdThatExistsIsNotNullExceptOnThePlatformLevelTables() {
        Set<String> nullable = new TreeSet<>(jdbc.sql("""
                SELECT table_schema || '.' || table_name FROM information_schema.columns
                WHERE column_name = 'tenant_id' AND is_nullable = 'YES' AND table_schema IN (%s)
                """.formatted(DOMAIN_SCHEMAS)).query(String.class).list());

        // audit_logs and outbox_events describe platform-level actions too; user_roles holds platform roles (docs/kafka-event-catalog.md), and
        // so do the read-model copies of it that Academic and each service on top of it keep (V80, V81).
        assertThat(nullable).containsExactlyInAnyOrder("governance.audit_logs", "governance.outbox_events", "identity.user_roles",
            "institution.rm_role_grants", "learning.rm_role_grants", "assessment.rm_role_grants", "attendance.rm_role_grants");
    }

    /** Tables that legitimately have no tenant: the login account itself and global reference data, the college row that IS the tenant, and per-user preferences. */
    private static final Set<String> PLATFORM_TABLES = Stream.concat(Stream.of(
        "identity.users", "identity.persons", "identity.passkeys", "identity.sessions", "identity.password_reset_codes",
        "identity.password_reset_tokens", "identity.webauthn_challenges", "identity.roles", "identity.permissions",
        "identity.role_permissions", "identity.user_roles", "institution.colleges", "governance.audit_logs",
        "governance.outbox_events", "governance.idempotency_keys", "notification.notification_preferences",
        "notification.processed_events",
        // The Academic service's read-model copies of the Identity tables above (6A.3): same platform-level data, same absence of a tenant.
        "institution.rm_role_permissions", "institution.rm_persons", "institution.rm_processed_events"),
        // The same copies in the learning, assessment and attendance schemas (V81): the role matrix, the processed-event record, and the
        // permissions of a guardian grant (a child of rm_guardian_grants, which has the tenant; a read model keeps no foreign keys).
        Stream.of("learning", "assessment", "attendance").flatMap(schema -> Stream.of(schema + ".rm_role_permissions",
            schema + ".rm_processed_events", schema + ".rm_guardian_grant_permissions"))).collect(java.util.stream.Collectors.toUnmodifiableSet());

    @Test
    void everyTableIsTenantOwnedOrBelongsToATenantOwnedParent() {
        Set<String> withTenant = new HashSet<>(jdbc.sql("""
                SELECT table_schema || '.' || table_name FROM information_schema.columns
                WHERE column_name = 'tenant_id' AND table_schema IN (%s)
                """.formatted(DOMAIN_SCHEMAS)).query(String.class).list());
        List<String> allTables = jdbc.sql("""
                SELECT table_schema || '.' || table_name FROM information_schema.tables
                WHERE table_type = 'BASE TABLE' AND table_schema IN (%s)
                """.formatted(DOMAIN_SCHEMAS)).query(String.class).list();
        // child table -> tables it has a foreign key to
        Map<String, Set<String>> parents = new HashMap<>();
        jdbc.sql("""
                SELECT cn.nspname || '.' || c.relname, pn.nspname || '.' || p.relname
                FROM pg_constraint k
                JOIN pg_class c ON c.oid = k.conrelid JOIN pg_namespace cn ON cn.oid = c.relnamespace
                JOIN pg_class p ON p.oid = k.confrelid JOIN pg_namespace pn ON pn.oid = p.relnamespace
                WHERE k.contype = 'f'
                """).query((rs, i) -> new String[] {rs.getString(1), rs.getString(2)}).list()
            .forEach(pair -> parents.computeIfAbsent(pair[0], x -> new HashSet<>()).add(pair[1]));

        List<String> orphans = allTables.stream()
            .filter(t -> !withTenant.contains(t) && !PLATFORM_TABLES.contains(t))
            .filter(t -> !reachesTenantOwner(t, parents, withTenant))
            .sorted().toList();

        assertThat(orphans).as("tables with no tenant_id that also have no foreign-key path to a tenant-owned table").isEmpty();
    }

    private static boolean reachesTenantOwner(String start, Map<String, Set<String>> parents, Set<String> withTenant) {
        Deque<String> queue = new ArrayDeque<>(parents.getOrDefault(start, Set.of()));
        Set<String> seen = new HashSet<>();
        while (!queue.isEmpty()) {
            String t = queue.poll();
            if (!seen.add(t)) {
                continue;
            }
            if (withTenant.contains(t)) {
                return true;
            }
            queue.addAll(parents.getOrDefault(t, Set.of()));
        }
        return false;
    }

    private Map<String, String> columnsByTable(String... qualified) {
        Map<String, String> out = new HashMap<>();
        for (String q : qualified) {
            String[] parts = q.split("\\.");
            out.put(q, String.join(",", jdbc.sql("""
                    SELECT column_name FROM information_schema.columns WHERE table_schema = :s AND table_name = :t ORDER BY ordinal_position
                    """).param("s", parts[0]).param("t", parts[1]).query(String.class).list()));
        }
        return out;
    }
}
