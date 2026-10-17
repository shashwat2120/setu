package com.setu.risk.infrastructure.kafka;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * The topics the risk consumer reads: the outbox publisher's {@code <prefix><aggregate_type>} names for the aggregates whose events change a
 * student's risk inputs. Built from the same prefix the publisher uses, so the two cannot drift apart.
 */
@Component("riskTopics")
@ConditionalOnProperty(name = "app.risk.consumer.enabled", havingValue = "true", matchIfMissing = true)
public class RiskTopics {

    private final String[] names;

    RiskTopics(@Value("${app.risk.consumer.topic-prefix:${app.outbox.topic-prefix:setu.}}") String prefix,
               @Value("${app.risk.consumer.aggregates:grades}") String aggregates) {
        this.names = Arrays.stream(aggregates.split(",")).map(String::trim).filter(a -> !a.isEmpty()).map(a -> prefix + a).toArray(String[]::new);
    }

    public String[] names() {
        return names.clone();
    }
}
