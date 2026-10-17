package com.setu.risk.infrastructure.kafka;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The risk consumer, switched on, is registered the way the design says and does not disturb its neighbour. The starter is replaced by a mock so
 * the test never opens a Kafka client (the development machine cannot, and a test must not consume the shared topics).
 */
@SpringBootTest(properties = "app.risk.consumer.enabled=true")
class RiskConsumerWiringTests {

    @MockitoBean
    RiskConsumerStarter starter;

    @Autowired
    KafkaListenerEndpointRegistry registry;
    @Autowired
    ApplicationContext context;

    @Test
    void theListenerReadsTheGradeTopicInItsOwnGroupFromTheEarliestOffset() {
        MessageListenerContainer container = registry.getListenerContainer(RiskEventListener.ID);

        assertThat(container).as("registered under its id").isNotNull();
        assertThat(container.isRunning()).as("not started with the context; the starter does that, with retries").isFalse();
        assertThat(container.getContainerProperties().getTopics()).containsExactly("setu.grades");
        assertThat(container.getGroupId()).isEqualTo("setu-risk");
        assertThat(container.getContainerProperties().getKafkaConsumerProperties().getProperty("auto.offset.reset")).isEqualTo("earliest");
    }

    @Test
    void itsRetryPolicyIsNotACommonErrorHandlerBeanThatWouldSwitchOffTheNotificationConsumersOwn() {
        assertThat(context.getBeansOfType(CommonErrorHandler.class))
            .as("Spring Boot applies the one CommonErrorHandler bean to the default factory and none if there are two")
            .isEmpty();
        assertThat(context.containsBean(RiskKafkaConfig.CONTAINER_FACTORY)).isTrue();
    }
}
