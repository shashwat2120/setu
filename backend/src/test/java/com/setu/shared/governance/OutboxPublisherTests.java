package com.setu.shared.governance;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Day 15 §2: {@link OutboxPublisher}'s database-side correctness — real
 * PostgreSQL {@code FOR UPDATE SKIP LOCKED} concurrency, real batch
 * selection, real status transition — verified with the actual
 * Spring-managed, {@code @Transactional}-proxied bean.
 * <p>
 * <b>Disclosed environment limitation</b>: the {@link KafkaTemplate} bean
 * here is replaced with a lightweight recording stub, NOT the real Kafka
 * client. This machine has a JDK/Windows defect (confirmed identical to
 * the one worked around for Redis via Jedis and for Ollama via
 * HttpClient5 in Days 14-15 — {@code java.nio.channels.Selector.open()}
 * fails with "Unable to establish loopback connection" via {@code
 * sun.nio.ch.PipeImpl$Initializer$LoopbackConnector}) that prevents
 * constructing a real {@code KafkaProducer} at all — confirmed via a
 * direct run of this exact test against the real {@code setu-kafka-1}
 * container, which failed at producer construction, before ever reaching
 * the network. Unlike Redis (swapped to Jedis) or Ollama's HTTP client
 * (swapped to HttpClient5's classic API), Kafka's official Java client has
 * no alternative blocking-socket implementation to swap to — this is a
 * genuine, disclosed limitation of this specific dev machine, not of the
 * code under test. The stub below proves every piece of {@link
 * OutboxPublisher}'s OWN logic (which topic/key/headers it builds, which
 * rows it claims, when it marks a row published, that two concurrent
 * runs never double-claim the same row) with full fidelity; only the
 * actual Kafka wire-protocol delivery itself could not be exercised here.
 */
@SpringBootTest
class OutboxPublisherTests {

    @TestConfiguration
    static class StubKafkaConfig {
        @Bean
        @Primary
        KafkaTemplate<String, String> recordingKafkaTemplate() {
            return new RecordingKafkaTemplate();
        }
    }

    /** Records every send and always succeeds — the network boundary this environment cannot exercise for real (see class javadoc). */
    static class RecordingKafkaTemplate extends KafkaTemplate<String, String> {
        final List<ProducerRecord<String, String>> sent = new CopyOnWriteArrayList<>();

        @SuppressWarnings("unchecked")
        RecordingKafkaTemplate() {
            // Never actually used to create a real producer — send(ProducerRecord) is fully overridden below, the
            // only overload OutboxPublisher calls; this factory just satisfies the constructor's not-null check.
            super(org.mockito.Mockito.mock(org.springframework.kafka.core.ProducerFactory.class));
        }

        @Override
        public CompletableFuture<SendResult<String, String>> send(ProducerRecord<String, String> record) {
            sent.add(record);
            TopicPartition tp = new TopicPartition(record.topic(), 0);
            RecordMetadata metadata = new RecordMetadata(tp, 0, sent.size(), 0, 0, 0);
            return CompletableFuture.completedFuture(new SendResult<>(record, metadata));
        }
    }

    @Autowired
    OutboxEventRepository repository;
    @Autowired
    OutboxPublisher publisher;
    @Autowired
    KafkaTemplate<String, String> kafkaTemplate;

    private RecordingKafkaTemplate recording() {
        return (RecordingKafkaTemplate) kafkaTemplate;
    }

    /**
     * {@code lockNextBatchForPublishing} deliberately has no per-test
     * scoping — in real usage it must sweep every NEW row in the table,
     * regardless of which module wrote it. That means leftover NEW rows
     * from unrelated tests earlier in this same class (or, if run
     * alongside the whole suite, from any other test that writes to the
     * outbox) would otherwise be swept into THIS test's batches
     * unpredictably. Clearing the table first makes each test's starting
     * state deterministic without weakening the publisher itself.
     */
    @BeforeEach
    void clearOutboxTable() {
        repository.deleteAll();
    }

    private OutboxEvent writeTestRow(String aggregateType, UUID aggregateId, String eventType) {
        UUID correlationId = UUID.randomUUID();
        OutboxEvent event = new OutboxEvent(UUID.randomUUID(), UUID.randomUUID(), aggregateType, aggregateId,
            eventType, 1, "{\"test\":true}", Instant.now(), correlationId, null, "test", 1);
        return repository.saveAndFlush(event);
    }

    @Test
    void publishedRowIsMarkedPublishedWithCorrectTopicKeyAndHeaders() {
        String aggregateType = "test_outbox_" + UUID.randomUUID().toString().substring(0, 8);
        UUID aggregateId = UUID.randomUUID();
        OutboxEvent row = writeTestRow(aggregateType, aggregateId, "TestEvent");

        publisher.publishPending();

        OutboxEvent reloaded = repository.findById(row.id()).orElseThrow();
        assertThat(reloaded.status()).isEqualTo(OutboxEvent.OutboxStatus.PUBLISHED);

        ProducerRecord<String, String> record = recording().sent.stream()
            .filter(r -> r.key().equals(aggregateId.toString())).findFirst().orElseThrow();
        assertThat(record.topic()).isEqualTo("setu." + aggregateType);
        // Whitespace only: JSONB round-trips through Postgres with reformatted spacing (same finding as Day 13's
        // feature_snapshot) — the value is read back from the row after saveAndFlush, not the original literal.
        assertThat(record.value()).contains("\"test\"").contains("true");
        assertThat(headerValue(record, "event_type")).isEqualTo("TestEvent");
        assertThat(headerValue(record, "event_id")).isEqualTo(row.id().toString());
        assertThat(headerValue(record, "correlation_id")).isEqualTo(row.correlationId().toString());
    }

    @Test
    void unpublishedRowsAreLeftAloneUntilPolled() {
        String aggregateType = "test_outbox_untouched_" + UUID.randomUUID().toString().substring(0, 8);
        OutboxEvent row = writeTestRow(aggregateType, UUID.randomUUID(), "UntouchedEvent");

        // No publisher.publishPending() call — the row must still be NEW.
        OutboxEvent reloaded = repository.findById(row.id()).orElseThrow();
        assertThat(reloaded.status()).isEqualTo(OutboxEvent.OutboxStatus.NEW);
    }

    /** TEST: FOR UPDATE SKIP LOCKED — real PostgreSQL, two threads concurrently draining the SAME two rows must never both claim the same row; both rows end up published exactly once each, never zero, never twice. */
    @Test
    void concurrentPublisherRuns_neverDoubleProcessTheSameRow() throws Exception {
        String aggregateType = "test_outbox_concurrent_" + UUID.randomUUID().toString().substring(0, 8);
        OutboxEvent rowA = writeTestRow(aggregateType, UUID.randomUUID(), "ConcurrentEventA");
        OutboxEvent rowB = writeTestRow(aggregateType, UUID.randomUUID(), "ConcurrentEventB");
        int before = recording().sent.size();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    await(start);
                    publisher.publishPending();
                }));
            }
            ready.await(10, TimeUnit.SECONDS);
            start.countDown();
            for (Future<?> f : futures) {
                f.get(15, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdown();
        }

        assertThat(repository.findById(rowA.id()).orElseThrow().status()).isEqualTo(OutboxEvent.OutboxStatus.PUBLISHED);
        assertThat(repository.findById(rowB.id()).orElseThrow().status()).isEqualTo(OutboxEvent.OutboxStatus.PUBLISHED);

        long deliveriesForThisTest = recording().sent.size() - before;
        assertThat(deliveriesForThisTest).isEqualTo(2); // exactly one send per row, never a double-publish
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String headerValue(ProducerRecord<String, String> record, String key) {
        var header = record.headers().lastHeader(key);
        return header == null ? null : new String(header.value(), java.nio.charset.StandardCharsets.UTF_8);
    }
}
