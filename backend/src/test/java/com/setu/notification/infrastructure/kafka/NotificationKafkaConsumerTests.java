package com.setu.notification.infrastructure.kafka;

import com.setu.notification.application.NotificationEventProcessor;
import com.setu.testsupport.Day7TestFixtures;
import com.setu.testsupport.Day8TestFixtures;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * The notification consumer against the real broker from the Docker stack: a published event produces a notification, a redelivery does
 * not produce a second one, a transient failure is retried until it succeeds, and a failure that never clears (or a poison message) ends
 * on {@code {topic}.DLQ} instead of blocking the topic.
 * <p>
 * Isolated from the live topics: it reads and writes topics under a random prefix, in its own consumer group, so it neither consumes
 * real events nor leaves any for the running application.
 */
@SpringBootTest(properties = {"app.notification.consumer.enabled=true", "app.notification.consumer.retry-initial-ms=50",
    "app.notification.consumer.aggregates=student_profiles"})
class NotificationKafkaConsumerTests {

    private static final String RUN = "ktest" + UUID.randomUUID().toString().substring(0, 8);
    private static final String TOPIC = RUN + ".student_profiles";

    /**
     * On the Windows development machine the JDK cannot open an NIO selector reliably (see backend/README.md), and neither can the Kafka
     * client. That is an environment fault, not a failure of the consumer: skip rather than report a false red, and run everywhere else.
     */
    @BeforeAll
    static void needsWorkingNio() {
        boolean ok;
        try (java.nio.channels.Selector ignored = java.nio.channels.Selector.open()) {
            ok = true;
        } catch (java.io.IOException e) {
            ok = false;
        }
        Assumptions.assumeTrue(ok, "this JVM cannot open an NIO selector, so no Kafka client can start");
    }

    @DynamicPropertySource
    static void isolatedTopicsAndGroup(DynamicPropertyRegistry registry) {
        registry.add("app.notification.consumer.topic-prefix", () -> RUN + ".");
        registry.add("app.notification.consumer.group-id", () -> RUN + "-group");
    }

    @Autowired
    KafkaTemplate<String, String> kafka;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    Day7TestFixtures fixtures;
    @Autowired
    Day8TestFixtures day8Fixtures;
    @MockitoSpyBean
    NotificationEventProcessor processor;
    @Value("${spring.kafka.bootstrap-servers}")
    String bootstrapServers;

    private record Student(Day7TestFixtures.Tenant tenant, Day8TestFixtures.Student student) {
    }

    private Student student(String prefix) {
        var tenant = fixtures.provisionTenant(prefix);
        UUID dept = fixtures.provisionDepartment(tenant, prefix + "D");
        UUID programId = fixtures.provisionProgram(tenant, dept, prefix + "P");
        UUID curriculumId = fixtures.provisionCurriculum(tenant, programId, "v1", java.time.LocalDate.of(2025, 1, 1), null);
        return new Student(tenant, day8Fixtures.provisionStudentWithActiveProgram(tenant, programId, curriculumId, prefix + "S"));
    }

    private void publish(UUID eventId, UUID tenantId, String eventType, String payload) throws Exception {
        ProducerRecord<String, String> record = new ProducerRecord<>(TOPIC, UUID.randomUUID().toString(), payload);
        record.headers().add("event_id", eventId.toString().getBytes(StandardCharsets.UTF_8));
        record.headers().add("event_type", eventType.getBytes(StandardCharsets.UTF_8));
        if (tenantId != null) {
            record.headers().add("tenant_id", tenantId.toString().getBytes(StandardCharsets.UTF_8));
        }
        kafka.send(record).get(30, TimeUnit.SECONDS);
    }

    private static String admitted(UUID eventId, Student s) {
        return "{\"eventId\":\"" + eventId + "\",\"tenantId\":\"" + s.tenant.tenantId() + "\",\"studentId\":\"" + s.student.studentId() + "\"}";
    }

    private int notifications(UUID userId) {
        return jdbc.sql("SELECT count(*) FROM notification.notifications WHERE user_id = :u").param("u", userId).query(Integer.class).single();
    }

    private static void awaitTrue(String what, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("timed out waiting for: " + what);
    }

    /** Waits until the DLQ topic holds a record whose original-topic header and key we recognise, and returns its value. */
    private String awaitDeadLetter(String key) throws Exception {
        Map<String, Object> config = Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
            ConsumerConfig.GROUP_ID_CONFIG, RUN + "-dlq-reader-" + UUID.randomUUID(),
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        try (KafkaConsumer<String, String> reader = new KafkaConsumer<>(config)) {
            reader.subscribe(List.of(TOPIC + ".DLQ"));
            while (System.nanoTime() < deadline) {
                for (ConsumerRecord<String, String> r : reader.poll(Duration.ofMillis(500))) {
                    if (key.equals(r.key())) {
                        return r.value();
                    }
                }
            }
        }
        throw new AssertionError("no record with key " + key + " reached " + TOPIC + ".DLQ");
    }

    @Test
    void aPublishedEventBecomesANotificationAndARedeliveryDoesNot() throws Exception {
        var s = student("KC1");
        UUID eventId = UUID.randomUUID();
        String payload = admitted(eventId, s);

        publish(eventId, s.tenant.tenantId(), "StudentAdmitted", payload);
        awaitTrue("the notification", () -> notifications(s.student.userId()) == 1);

        // Same event id delivered again, as at-least-once delivery allows. A second, different event proves the first redelivery was
        // consumed (it is behind it on the same partition set) before we assert nothing extra appeared.
        publish(eventId, s.tenant.tenantId(), "StudentAdmitted", payload);
        UUID markerEvent = UUID.randomUUID();
        var marker = student("KC1M");
        publish(markerEvent, marker.tenant.tenantId(), "StudentAdmitted", admitted(markerEvent, marker));
        awaitTrue("the marker notification", () -> notifications(marker.student.userId()) == 1);

        assertThat(notifications(s.student.userId())).isEqualTo(1);
    }

    @Test
    void aTransientFailureIsRetriedAndThenSucceeds() throws Exception {
        var s = student("KC2");
        UUID eventId = UUID.randomUUID();
        // Fail the first two deliveries of this event only, then behave normally: the third attempt must succeed.
        doThrow(new IllegalStateException("simulated database blip")).doThrow(new IllegalStateException("simulated database blip"))
            .doCallRealMethod().when(processor).process(eq("StudentAdmitted"), eq(eventId.toString()), any(), any());

        publish(eventId, s.tenant.tenantId(), "StudentAdmitted", admitted(eventId, s));

        awaitTrue("the notification after retries", () -> notifications(s.student.userId()) == 1);
        verify(processor, times(3)).process(eq("StudentAdmitted"), eq(eventId.toString()), any(), any());
    }

    @Test
    void aFailureThatNeverClearsIsDeadLetteredAfterThreeAttempts() throws Exception {
        var s = student("KC3");
        UUID eventId = UUID.randomUUID();
        doThrow(new IllegalStateException("simulated outage")).when(processor)
            .process(eq("StudentAdmitted"), eq(eventId.toString()), any(), any());
        String key = UUID.randomUUID().toString();
        String payload = admitted(eventId, s);
        ProducerRecord<String, String> record = new ProducerRecord<>(TOPIC, key, payload);
        record.headers().add("event_id", eventId.toString().getBytes(StandardCharsets.UTF_8));
        record.headers().add("event_type", "StudentAdmitted".getBytes(StandardCharsets.UTF_8));
        kafka.send(record).get(30, TimeUnit.SECONDS);

        assertThat(awaitDeadLetter(key)).isEqualTo(payload);

        verify(processor, times(3)).process(eq("StudentAdmitted"), eq(eventId.toString()), any(), any());
        assertThat(notifications(s.student.userId())).isZero();
    }

    @Test
    void aPoisonMessageIsDeadLetteredWithoutRetries() throws Exception {
        UUID eventId = UUID.randomUUID();
        String key = UUID.randomUUID().toString();
        ProducerRecord<String, String> record = new ProducerRecord<>(TOPIC, key, "this is not json");
        record.headers().add("event_id", eventId.toString().getBytes(StandardCharsets.UTF_8));
        record.headers().add("event_type", "StudentAdmitted".getBytes(StandardCharsets.UTF_8));
        kafka.send(record).get(30, TimeUnit.SECONDS);

        assertThat(awaitDeadLetter(key)).isEqualTo("this is not json");

        verify(processor, times(1)).process(eq("StudentAdmitted"), eq(eventId.toString()), any(), eq("this is not json"));
    }
}
