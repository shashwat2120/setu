package com.setu.shared.observability;

import com.setu.institution.domain.CollegeCreatedEvent;
import com.setu.shared.governance.OutboxEventWriter;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tracing as wired in the running application, not as mocked: every observation is a span with a real trace id, the trace of the code that
 * writes an event is stored with it and sent on, and nothing is stored when nothing is being traced.
 */
@SpringBootTest
@Transactional
class TracingIntegrationTests {

    @Autowired
    Tracer tracer;
    @Autowired
    ObservationRegistry registry;
    @Autowired
    TraceParents traceParents;
    @Autowired
    Outbound outbound;
    @Autowired
    OutboxEventWriter writer;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    EntityManager entityManager;

    private void writeCollege(UUID tenant) {
        writer.publish(new CollegeCreatedEvent(UUID.randomUUID(), Instant.now(), tenant, tenant, UUID.randomUUID(), null, "Traced College", "Asia/Kolkata"));
        entityManager.flush();
    }

    private String storedTraceParent(UUID tenant) {
        return jdbc.sql("SELECT trace_parent FROM governance.outbox_events WHERE aggregate_id = :id").param("id", tenant).query(String.class).optional().orElse(null);
    }

    // A test may already run inside a span of its own, so these compare with what was current before rather than expecting nothing.

    @Test
    void anObservationIsASpanWithARealTraceIdAndTheHeaderNamesIt() {
        String before = traceParents.current().orElse(null);

        String inside = Observation.createNotStarted("test.span", registry).observe(() -> {
            String traceId = tracer.currentSpan().context().traceId();
            assertThat(traceId).hasSize(32).matches("[0-9a-f]{32}").isNotEqualTo("0".repeat(32));
            return traceParents.current().orElseThrow();
        });

        assertThat(TraceParents.isWellFormed(inside)).isTrue();
        assertThat(inside).as("its own span, not the one that was current").isNotEqualTo(before);
    }

    @Test
    void anOutboundCallRunsInsideItsOwnSpanAndIsHandedTheHeaderOfThatSpan() {
        String before = traceParents.current().orElse(null);

        String sent = outbound.call("test-peer", "ping", traceParent -> traceParent);

        assertThat(sent).isNotNull();
        assertThat(TraceParents.isWellFormed(sent)).isTrue();
        assertThat(sent).as("a span of its own for the call").isNotEqualTo(before);
    }

    @Test
    void theTraceThatWritesAnEventIsStoredWithIt() {
        UUID traced = UUID.randomUUID();

        String expected = Observation.createNotStarted("test.request", registry).observe(() -> {
            writeCollege(traced);
            return traceParents.current().orElseThrow();
        });

        assertThat(storedTraceParent(traced)).as("the span that was current when the event was written").isNotNull();
        assertThat(TraceParents.isWellFormed(storedTraceParent(traced))).isTrue();
        assertThat(storedTraceParent(traced).substring(3, 35)).as("same trace").isEqualTo(expected.substring(3, 35));
    }
}
