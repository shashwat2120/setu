package com.setu.shared.readmodel.kafka;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * The topics a service follows to keep its read model: the outbox publisher's {@code <prefix><aggregate_type>} names for the aggregates
 * listed in {@code app.readmodel.consumer.aggregates}. Built from the same prefix the publisher uses, so the two cannot drift apart.
 */
@Component("readModelTopics")
@ConditionalOnProperty(name = "app.readmodel.consumer.enabled", havingValue = "true")
public class ReadModelTopics {

    private final String[] names;

    ReadModelTopics(@Value("${app.readmodel.consumer.topic-prefix:${app.outbox.topic-prefix:setu.}}") String prefix,
                    @Value("${app.readmodel.consumer.aggregates}") String aggregates) {
        this.names = Arrays.stream(aggregates.split(",")).map(String::trim).filter(a -> !a.isEmpty()).map(a -> prefix + a).toArray(String[]::new);
    }

    public String[] names() {
        return names.clone();
    }
}
