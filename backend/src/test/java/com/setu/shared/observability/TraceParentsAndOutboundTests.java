package com.setu.shared.observability;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * How a trace crosses the hops nothing instruments for us (docs/contracts/observability.md): the {@code traceparent} is the current span in the
 * standard form, and nothing malformed is ever produced or accepted; an outbound call is observed (a span and a timer named for the peer and
 * the operation) and is handed the header to send; observing a call changes nothing about its result or its failure.
 */
class TraceParentsAndOutboundTests {

    private static final String TRACE = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String SPAN = "00f067aa0ba902b7";

    @SuppressWarnings("unchecked")
    private static TraceParents parentsFor(String traceId, String spanId, Boolean sampled) {
        TraceContext context = mock(TraceContext.class);
        when(context.traceId()).thenReturn(traceId);
        when(context.spanId()).thenReturn(spanId);
        when(context.sampled()).thenReturn(sampled);
        Span span = mock(Span.class);
        when(span.context()).thenReturn(context);
        Tracer tracer = mock(Tracer.class);
        when(tracer.currentSpan()).thenReturn(span);
        ObjectProvider<Tracer> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(tracer);
        return new TraceParents(provider);
    }

    @SuppressWarnings("unchecked")
    private static TraceParents noTracer() {
        ObjectProvider<Tracer> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return new TraceParents(provider);
    }

    @Test
    void theCurrentSpanIsTheStandardW3cHeader() {
        assertThat(parentsFor(TRACE, SPAN, true).current()).contains("00-" + TRACE + "-" + SPAN + "-01");
        assertThat(parentsFor(TRACE, SPAN, false).current()).as("not sampled").contains("00-" + TRACE + "-" + SPAN + "-00");
        assertThat(parentsFor(TRACE, SPAN, null).current()).as("unknown is not claimed to be sampled").contains("00-" + TRACE + "-" + SPAN + "-00");
        assertThat(parentsFor("A" + TRACE.substring(1).toUpperCase(), SPAN.toUpperCase(), true).current().orElseThrow()).as("always lower case")
            .isEqualTo("00-a" + TRACE.substring(1) + "-" + SPAN + "-01");
    }

    @Test
    void aShortTraceIdFromAnotherTracerIsPaddedToTheStandardLength() {
        assertThat(parentsFor("a3ce929d0e0e4736", SPAN, true).current()).contains("00-0000000000000000a3ce929d0e0e4736-" + SPAN + "-01");
    }

    @Test
    void thereIsNothingToSendWithoutATracerASpanOrARealTrace() {
        assertThat(noTracer().current()).isEmpty();
        assertThat(parentsFor("0".repeat(32), SPAN, true).current()).as("the all-zero id means no trace").isEmpty();
        assertThat(parentsFor(null, SPAN, true).current()).isEmpty();
        assertThat(parentsFor("not-hex-at-all-not-hex-at-all-xxxx", SPAN, true).current()).as("malformed is dropped, not forwarded").isEmpty();
    }

    @Test
    void onlyAWellFormedTraceparentCountsAsOne() {
        assertThat(TraceParents.isWellFormed("00-" + TRACE + "-" + SPAN + "-01")).isTrue();
        for (String bad : new String[] {null, "", "00-" + TRACE + "-" + SPAN, "01-" + TRACE + "-" + SPAN + "-01",
            "00-" + TRACE.toUpperCase() + "-" + SPAN + "-01", "00-" + TRACE + "-" + SPAN + "-01\r\nX-Injected: 1", "00-" + TRACE + "-" + SPAN + "-0g",
            "00-" + TRACE.substring(1) + "-" + SPAN + "-01"}) {
            assertThat(TraceParents.isWellFormed(bad)).as(String.valueOf(bad)).isFalse();
        }
    }

    // --- Outbound

    private record Seen(String name, List<KeyValue> low, boolean errored) {
    }

    private static ObservationRegistry registryRecordingInto(List<Seen> seen) {
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new ObservationHandler<Observation.Context>() {
            @Override
            public boolean supportsContext(Observation.Context context) {
                return true;
            }

            @Override
            public void onStop(Observation.Context context) {
                seen.add(new Seen(context.getName(), new ArrayList<>(context.getLowCardinalityKeyValues().stream().toList()), context.getError() != null));
            }
        });
        return registry;
    }

    @Test
    void anOutboundCallIsOneObservationNamedForThePeerAndTheOperationAndGetsTheHeaderToSend() throws Exception {
        List<Seen> seen = new ArrayList<>();
        AtomicReference<String> sent = new AtomicReference<>();
        Outbound outbound = new Outbound(registryRecordingInto(seen), parentsFor(TRACE, SPAN, true));

        String result = outbound.call("data-service", "ai-facts", traceParent -> {
            sent.set(traceParent);
            return "answer";
        });

        assertThat(result).isEqualTo("answer");
        assertThat(sent.get()).isEqualTo("00-" + TRACE + "-" + SPAN + "-01");
        assertThat(seen).hasSize(1);
        assertThat(seen.get(0).name()).isEqualTo("setu.outbound");
        assertThat(seen.get(0).low()).contains(KeyValue.of("peer", "data-service"), KeyValue.of("operation", "ai-facts"));
        assertThat(seen.get(0).errored()).isFalse();
    }

    @Test
    void aFailureIsRecordedOnTheObservationAndRethrownUnchanged() {
        List<Seen> seen = new ArrayList<>();
        Outbound outbound = new Outbound(registryRecordingInto(seen), noTracer());
        IOException failure = new IOException("connection refused");

        assertThatThrownBy(() -> outbound.<String, IOException>call("academic", "guardian-grant-verify", traceParent -> {
            throw failure;
        })).isSameAs(failure);

        assertThat(seen).hasSize(1);
        assertThat(seen.get(0).errored()).isTrue();
    }

    @Test
    void withoutObservabilityTheCallJustRunsAndSendsNoHeader() throws Exception {
        AtomicReference<String> sent = new AtomicReference<>("untouched");

        assertThat(Outbound.NONE.<String, RuntimeException>call("x", "y", traceParent -> {
            sent.set(traceParent);
            return "ok";
        })).isEqualTo("ok");
        assertThat(sent.get()).isNull();

        // a registry that does nothing is treated the same way
        sent.set("untouched");
        assertThat(new Outbound(ObservationRegistry.NOOP, parentsFor(TRACE, SPAN, true)).<String, RuntimeException>call("x", "y", tp -> {
            sent.set(tp);
            return "ok";
        })).isEqualTo("ok");
        assertThat(sent.get()).isNull();
        assertThat(Optional.ofNullable(sent.get())).isEmpty();
    }
}
