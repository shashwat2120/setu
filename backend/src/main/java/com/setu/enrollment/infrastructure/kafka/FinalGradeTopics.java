package com.setu.enrollment.infrastructure.kafka;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * The topics the enrollment consumer reads: the outbox publisher's {@code <prefix><aggregate_type>} names for the aggregates whose events can
 * complete an enrollment (grades). Built from the same prefix the publisher uses, so the two cannot drift apart.
 */
@Component("finalGradeTopics")
@ConditionalOnProperty(name = "app.enrollment.consumer.enabled", havingValue = "true", matchIfMissing = true)
public class FinalGradeTopics {

    private final String[] names;

    FinalGradeTopics(@Value("${app.enrollment.consumer.topic-prefix:${app.outbox.topic-prefix:setu.}}") String prefix,
               @Value("${app.enrollment.consumer.aggregates:grades}") String aggregates) {
        this.names = Arrays.stream(aggregates.split(",")).map(String::trim).filter(a -> !a.isEmpty()).map(a -> prefix + a).toArray(String[]::new);
    }

    public String[] names() {
        return names.clone();
    }
}
