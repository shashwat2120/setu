package com.setu.shared.governance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.setu.institution.domain.CollegeCreatedEvent;
import com.setu.shared.observability.TraceParents;
import io.micrometer.tracing.Tracer;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** What the outbox stores about the trace when there is none to store: nothing, never a made-up or empty value. */
@SpringBootTest
@Transactional
class OutboxTraceParentTests {

    @Autowired
    OutboxEventRepository repository;
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    EntityManager entityManager;

    @Test
    @SuppressWarnings("unchecked")
    void nothingIsStoredWhenNothingIsBeingTraced() {
        UUID tenant = UUID.randomUUID();
        // the real writer and repository, with a tracer that has no span to offer (tracing off)
        ObjectProvider<Tracer> noTracer = mock(ObjectProvider.class);
        OutboxEventWriter untraced = new OutboxEventWriter(repository, objectMapper, new TraceParents(noTracer));

        untraced.publish(new CollegeCreatedEvent(UUID.randomUUID(), Instant.now(), tenant, tenant, UUID.randomUUID(), null, "Untraced", "Asia/Kolkata"));
        entityManager.flush();

        assertThat(jdbc.sql("SELECT trace_parent FROM governance.outbox_events WHERE aggregate_id = :id").param("id", tenant).query(String.class).optional())
            .as("the row exists and its trace_parent is null").isEmpty();
        assertThat(jdbc.sql("SELECT count(*) FROM governance.outbox_events WHERE aggregate_id = :id").param("id", tenant).query(Long.class).single()).isEqualTo(1L);
    }
}
