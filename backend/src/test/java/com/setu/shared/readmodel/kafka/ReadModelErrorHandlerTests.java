package com.setu.shared.readmodel.kafka;

import com.setu.shared.events.UnsupportedEventVersionException;
import com.setu.shared.readmodel.PoisonEventException;
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
 * The retry and dead-letter policy of the read-model consumer that every extracted service runs (docs/contracts/kafka-contracts.md), exercised
 * without a broker: a transient failure is delivered three times in all and then dead-lettered to {@code {topic}.DLQ}; an event that can never
 * succeed (poison, a constraint violation, a version this build does not understand) goes there on the first failure; and each record has its
 * own budget so one bad record never makes the next one give up early.
 */
class ReadModelErrorHandlerTests {

    private static final String TOPIC = "setu.students";

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
        handler = new ReadModelKafkaConfig().readModelErrorHandler(template, meters, 5);

        consumer = new MockConsumer<>("earliest");
        consumer.assign(List.of(new TopicPartition(TOPIC, 0)));
        container = mock(MessageListenerContainer.class);
        when(container.isRunning()).thenReturn(true);
    }

    private static ConsumerRecord<String, String> record(long offset) {
        return new ConsumerRecord<>(TOPIC, 0, offset, "student-1", "{\"payload\":true}");
    }

    @Test
    void aTransientFailureIsDeliveredThreeTimesThenDeadLettered() {
        var record = record(7);
        var failure = new IllegalStateException("database unavailable");

        assertThat(handler.handleOne(failure, record, consumer, container)).as("first failure: retry").isFalse();
        assertThat(handler.handleOne(failure, record, consumer, container)).as("second failure: retry").isFalse();
        assertThat(dlqProducer.history()).isEmpty();

        assertThat(handler.handleOne(failure, record, consumer, container)).as("third failure: give up").isTrue();

        assertThat(dlqProducer.history()).hasSize(1);
        var dead = dlqProducer.history().get(0);
        assertThat(dead.topic()).isEqualTo(TOPIC + ".DLQ");
        assertThat(dead.key()).isEqualTo("student-1");
        assertThat(dead.value()).isEqualTo("{\"payload\":true}");
        assertThat(meters.counter("setu.readmodel.events.dead_lettered", "topic", TOPIC).count()).isEqualTo(1.0);
    }

    @Test
    void anEventThatCanNeverSucceedIsDeadLetteredOnTheFirstFailure() {
        assertThat(handler.handleOne(new PoisonEventException("malformed"), record(1), consumer, container)).isTrue();
        assertThat(handler.handleOne(new DataIntegrityViolationException("fk"), record(2), consumer, container)).isTrue();
        assertThat(handler.handleOne(new UnsupportedEventVersionException("StudentEnrolled version 2"), record(3), consumer, container))
            .as("a newer version waits in the dead-letter topic for the upgraded consumer, rather than being misread or retried").isTrue();

        assertThat(dlqProducer.history()).hasSize(3).allSatisfy(r -> assertThat(r.topic()).isEqualTo(TOPIC + ".DLQ"));
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
