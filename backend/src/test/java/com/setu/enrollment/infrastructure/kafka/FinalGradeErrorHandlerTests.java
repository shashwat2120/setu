package com.setu.enrollment.infrastructure.kafka;

import com.setu.enrollment.application.PoisonEventException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.MessageListenerContainer;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The retry and dead-letter policy of the enrollment consumer (docs/kafka-event-catalog.md "Retry/DLQ"), exercised without a broker: a transient
 * failure is delivered 3 times in all and then dead-lettered to {@code {topic}.DLQ}; a poison message is dead-lettered on the first failure, with
 * no retries. The same policy as the notification and read-model consumers, on the enrollment consumer's own container factory.
 */
class FinalGradeErrorHandlerTests {

    private static final String TOPIC = "setu.grades";

    /** A producer whose close() is a no-op, so the template can reuse it the way a caching producer factory would. */
    private static final class ReusableMockProducer extends MockProducer<String, String> {
        ReusableMockProducer() {
            super(true, null, new StringSerializer(), new StringSerializer());
        }

        @Override
        public void close() {
        }

        @Override
        public void close(Duration timeout) {
        }
    }

    private ReusableMockProducer dlqProducer;
    private SimpleMeterRegistry meters;
    private CommonErrorHandler handler;
    private MockConsumer<String, String> consumer;
    private MessageListenerContainer container;

    @BeforeEach
    void setUp() {
        dlqProducer = new ReusableMockProducer();
        @SuppressWarnings("unchecked")
        ProducerFactory<String, String> factory = mock(ProducerFactory.class);
        when(factory.createProducer()).thenReturn(dlqProducer);
        when(factory.transactionCapable()).thenReturn(false);
        KafkaTemplate<String, String> template = new KafkaTemplate<>(factory);
        meters = new SimpleMeterRegistry();
        handler = FinalGradeKafkaConfig.errorHandler(template, meters, 5);

        consumer = new MockConsumer<>("earliest");
        consumer.assign(List.of(new TopicPartition(TOPIC, 0)));
        container = mock(MessageListenerContainer.class);
        when(container.isRunning()).thenReturn(true);
    }

    private static ConsumerRecord<String, String> record(long offset) {
        return new ConsumerRecord<>(TOPIC, 0, offset, "enrollment-1", "{\"payload\":true}");
    }

    @Test
    void aTransientFailureIsRetriedTwiceThenDeadLettered() {
        var record = record(7);
        var failure = new IllegalStateException("database unavailable");

        assertThat(handler.handleOne(failure, record, consumer, container)).as("first failure: retry").isFalse();
        assertThat(handler.handleOne(failure, record, consumer, container)).as("second failure: retry").isFalse();
        assertThat(dlqProducer.history()).as("still nothing dead-lettered after two failures").isEmpty();

        assertThat(handler.handleOne(failure, record, consumer, container)).as("third failure: give up").isTrue();

        assertThat(dlqProducer.history()).hasSize(1);
        var dead = dlqProducer.history().get(0);
        assertThat(dead.topic()).isEqualTo(TOPIC + ".DLQ");
        assertThat(dead.key()).isEqualTo("enrollment-1");
        assertThat(dead.value()).isEqualTo("{\"payload\":true}");
        assertThat(meters.counter("setu.enrollment.events.dead_lettered", "topic", TOPIC).count()).isEqualTo(1.0);
    }

    @Test
    void aPoisonMessageIsDeadLetteredImmediatelyWithoutRetries() {
        assertThat(handler.handleOne(new PoisonEventException("cannot ever succeed"), record(1), consumer, container)).isTrue();

        assertThat(dlqProducer.history()).hasSize(1);
        assertThat(dlqProducer.history().get(0).topic()).isEqualTo(TOPIC + ".DLQ");
    }

    @Test
    void aConstraintViolationIsAlsoNotRetried() {
        assertThat(handler.handleOne(new DataIntegrityViolationException("fk"), record(2), consumer, container)).isTrue();

        assertThat(dlqProducer.history()).hasSize(1);
    }

    @Test
    void eachRecordHasItsOwnAttemptBudget() {
        var failure = new IllegalStateException("blip");
        handler.handleOne(failure, record(10), consumer, container);
        handler.handleOne(failure, record(10), consumer, container);

        assertThat(handler.handleOne(failure, record(11), consumer, container)).isFalse();
        assertThat(dlqProducer.history()).isEmpty();
    }
}
