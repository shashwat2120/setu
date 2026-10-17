package com.setu.config;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The connection pool is sized by a rule that cannot deadlock, not by feel (docs/contracts/performance.md).
 * <p>
 * A write that uses a nested {@code REQUIRES_NEW} transaction (the insert guards that turn a lost uniqueness race into a clean answer; 43
 * classes use one) holds its outer connection while the inner one is acquired, so a request in flight needs <b>two</b> connections. With more
 * requests in flight than half the pool, every connection is held by an outer transaction waiting for an inner one, and all of them stall until the
 * connection timeout. Measured on this project: 20 simultaneous QR scans against the default pool of 10 stalled for 30 seconds each.
 * <p>
 * The rule: {@code worker threads x 2 + background threads x 2 <= pool size}, where background threads are the outbox publisher, the schedulers
 * and the event consumers. Raising {@code server.tomcat.threads.max} without raising the pool, or lowering the pool, fails here, not in front of a
 * classroom.
 */
@SpringBootTest
class ConnectionPoolSizingTests {

    /** Threads that can hold a database connection besides request threads: the outbox publisher, schedulers, Kafka listeners. Generous on purpose. */
    static final int BACKGROUND_THREADS = 8;

    @Autowired
    Environment environment;
    @Autowired
    DataSource dataSource;

    @Test
    void thePoolIsLargeEnoughForEveryRequestThreadToNeedTwoConnectionsAtOnce() throws Exception {
        int workers = Integer.parseInt(environment.getRequiredProperty("server.tomcat.threads.max"));
        int pool = dataSource.unwrap(HikariDataSource.class).getMaximumPoolSize();

        assertThat(workers).as("worker threads").isPositive();
        assertThat(workers * 2 + BACKGROUND_THREADS * 2)
            .as("connections that can be needed at once (%d worker threads, %d background threads, two each) must fit the pool of %d", workers, BACKGROUND_THREADS, pool)
            .isLessThanOrEqualTo(pool);
    }

    /** What the database server allows (docker-compose.yml: max_connections=300), and the number of services that share it. */
    private static final int SERVER_CONNECTIONS = 300;
    private static final int SERVICES = 10;

    @Test
    void enoughConnectionsAreKeptReadyForABurstWithoutPayingToOpenThemAndFewEnoughForTenServicesToShareOneServer() throws Exception {
        int idle = dataSource.unwrap(HikariDataSource.class).getMinimumIdle();

        assertThat(idle).as("a classroom scanning at once should find connections open, not wait for them to be created one by one").isGreaterThanOrEqualTo(12);
        assertThat(idle * SERVICES).as("idle connections of all services together stay well inside what the database server accepts")
            .isLessThanOrEqualTo(SERVER_CONNECTIONS * 6 / 10);
    }
}
