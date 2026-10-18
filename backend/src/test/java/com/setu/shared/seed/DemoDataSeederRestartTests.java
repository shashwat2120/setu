package com.setu.shared.seed;

import com.setu.identity.infrastructure.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A service whose database outlives it (a container with a persistent volume) restarts with demo seeding still switched on, and must not die on
 * its own data. It used to: the seeder began again with the first account and failed with "already exists", so the core process of the
 * containerised stack never came back after a restart.
 * <p>
 * Seeding is many transactions, so "the super admin exists" cannot mean "done"; only the marker written at the very end counts, and data without
 * the marker still fails loudly. Own context (its own email base), so it neither shares nor disturbs another seeder test's data. The context
 * starts the seeder once (seeding, or skipping if this database was seeded by an earlier run of this test); the tests run it again.
 */
@SpringBootTest
@TestPropertySource(properties = {"app.demo-data.enabled=true", "app.demo-data.email-base=restart.demo.test@example.com"})
class DemoDataSeederRestartTests {

    private static final String SUPER_ADMIN = "restart.demo.test+super.admin@example.com";

    @Autowired
    DemoDataSeeder seeder;
    @Autowired
    UserRepository userRepository;
    @Autowired
    JdbcClient jdbc;

    private int markers() {
        return jdbc.sql("SELECT count(*) FROM governance.audit_logs WHERE action = 'DEMO_DATA_SEEDED' AND after_json ->> 'superAdmin' = :e")
            .param("e", SUPER_ADMIN).query(Integer.class).single();
    }

    @Test
    void aRestartAfterSeedingFinishedDoesNothingInsteadOfFailing() {
        assertThat(userRepository.findByEmail(SUPER_ADMIN)).as("seeded when the context started").isPresent();
        assertThat(markers()).as("the end of seeding is marked, once").isEqualTo(1);
        long accounts = jdbc.sql("SELECT count(*) FROM identity.users WHERE email LIKE 'restart.demo.test+%'").query(Long.class).single();

        assertThatCode(() -> seeder.run(null)).doesNotThrowAnyException();

        assertThat(jdbc.sql("SELECT count(*) FROM identity.users WHERE email LIKE 'restart.demo.test+%'").query(Long.class).single())
            .as("nothing was seeded twice").isEqualTo(accounts);
        assertThat(markers()).isEqualTo(1);
    }

    @Test
    void dataWithoutTheMarkerStillFailsLoudlyBecauseTheEarlierRunDidNotFinish() {
        jdbc.sql("DELETE FROM governance.audit_logs WHERE action = 'DEMO_DATA_SEEDED' AND after_json ->> 'superAdmin' = :e").param("e", SUPER_ADMIN).update();
        try {
            assertThatThrownBy(() -> seeder.run(null)).isInstanceOf(IllegalStateException.class).hasMessageContaining("already exists");
        } finally {
            // put the marker back, so the other test (and the next run of this class) sees a finished seeding
            jdbc.sql("INSERT INTO governance.audit_logs (action, resource_type, after_json) VALUES ('DEMO_DATA_SEEDED', 'demo-data', jsonb_build_object('superAdmin', :e))")
                .param("e", SUPER_ADMIN).update();
        }
    }
}
