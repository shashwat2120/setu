package com.setu.shared.observability;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The whole path, for real: the application, with export switched on, sends a span to Tempo and a log line to Loki (both of the compose stack,
 * docs/contracts/observability.md), and both can be read back by the trace id that appears in the log line. Skipped when the stack is not
 * running, like every test of this kind that needs infrastructure the machine may not have up.
 */
@SpringBootTest(properties = {
    "management.tracing.export.otlp.enabled=true",
    "management.opentelemetry.tracing.export.otlp.endpoint=http://localhost:4318/v1/traces",
    "management.opentelemetry.tracing.export.schedule-delay=200ms",
    "management.logging.export.otlp.enabled=true",
    "management.opentelemetry.logging.export.otlp.endpoint=http://localhost:3100/otlp/v1/logs",
    "management.opentelemetry.logging.export.schedule-delay=200ms",
    "spring.application.name=telemetry-export-test",
    "management.tracing.sampling.probability=1.0"})
class TelemetryExportTests {

    private static final Logger log = LoggerFactory.getLogger(TelemetryExportTests.class);
    private static final String TEMPO = "http://localhost:3200";
    private static final String LOKI = "http://localhost:3100";

    @Autowired
    Tracer tracer;
    @Autowired
    ObservationRegistry registry;

    @BeforeAll
    static void theStackIsUp() {
        Assumptions.assumeTrue(get(TEMPO + "/api/echo") != null && get(LOKI + "/ready") != null, "Tempo and Loki (docker compose) are not running");
    }

    private static String get(String url) {
        try {
            HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
            connection.setConnectTimeout(1500);
            connection.setReadTimeout(5000);
            if (connection.getResponseCode() != 200) {
                return null;
            }
            return new String(connection.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    @Test
    void aSpanAndALogLineOfTheApplicationReachTempoAndLokiUnderOneTraceId() {
        String marker = "telemetry-export-" + System.nanoTime();
        String traceId = Observation.createNotStarted("setu.telemetry-export-test", registry).observe(() -> {
            log.info("{}", marker);
            return tracer.currentSpan().context().traceId();
        });
        assertThat(traceId).matches("[0-9a-f]{32}");

        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofSeconds(1)).untilAsserted(() -> {
            String trace = get(TEMPO + "/api/traces/" + traceId);
            assertThat(trace).as("the span, in Tempo").isNotNull().contains("setu.telemetry-export-test").contains("telemetry-export-test");
        });

        long start = Instant.now().minusSeconds(300).getEpochSecond() * 1_000_000_000L;
        String query = URLEncoder.encode("{service_name=\"telemetry-export-test\"} |= \"" + marker + "\"", StandardCharsets.UTF_8);
        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofSeconds(1)).untilAsserted(() -> {
            String logs = get(LOKI + "/loki/api/v1/query_range?query=" + query + "&start=" + start);
            assertThat(logs).as("the log line, in Loki").isNotNull().contains(marker).contains(traceId);
        });
    }
}
