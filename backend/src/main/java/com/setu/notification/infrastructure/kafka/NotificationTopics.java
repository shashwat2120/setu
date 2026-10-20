package com.setu.notification.infrastructure.kafka;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * The topics the notification consumer reads: the outbox publisher's {@code <prefix><aggregate_type>} names for the aggregates whose
 * events can notify someone. Built from the same prefix the publisher uses, so the two cannot drift apart.
 */
@Component("notificationTopics")
@ConditionalOnProperty(name = "app.notification.consumer.enabled", havingValue = "true", matchIfMissing = true)
public class NotificationTopics {

    private final String[] names;

    NotificationTopics(@Value("${app.notification.consumer.topic-prefix:${app.outbox.topic-prefix:setu.}}") String prefix,
                       @Value("${app.notification.consumer.aggregates:grades,payments,placement_records,applications,recommendations,interventions,student_profiles,enrollments,waitlist_entries}") String aggregates) {
        this.names = Arrays.stream(aggregates.split(",")).map(String::trim).filter(a -> !a.isEmpty()).map(a -> prefix + a).toArray(String[]::new);
    }

    public String[] names() {
        return names.clone();
    }
}
