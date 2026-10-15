package com.setu.academic.infrastructure.kafka;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/** The Identity topics the Academic service reads: the outbox publisher's {@code <prefix><aggregate_type>} names for role assignments and people. */
@Component("academicIdentityTopics")
@ConditionalOnProperty(name = "app.academic.consumer.enabled", havingValue = "true")
public class AcademicIdentityTopics {

    private final String[] names;

    AcademicIdentityTopics(@Value("${app.academic.consumer.topic-prefix:${app.outbox.topic-prefix:setu.}}") String prefix,
                           @Value("${app.academic.consumer.aggregates:user_roles,persons}") String aggregates) {
        this.names = Arrays.stream(aggregates.split(",")).map(String::trim).filter(a -> !a.isEmpty()).map(a -> prefix + a).toArray(String[]::new);
    }

    public String[] names() {
        return names.clone();
    }
}
