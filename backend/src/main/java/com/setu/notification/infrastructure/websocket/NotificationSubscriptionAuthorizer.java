package com.setu.notification.infrastructure.websocket;

import com.setu.notification.application.LiveDestinations;
import com.setu.shared.websocket.OwnTopic;
import com.setu.shared.websocket.StompDestinationAuthorizer;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Who may listen to {@code /topic/notifications/<userId>}: that user, and nobody else, not an administrator and not the user's guardian.
 * A notification is addressed to one account, so the check is identity, not a permission ({@link OwnTopic}).
 */
@Component
public class NotificationSubscriptionAuthorizer implements StompDestinationAuthorizer {

    @Override
    public boolean supports(String destination) {
        return destination != null && destination.startsWith(LiveDestinations.PREFIX);
    }

    @Override
    public boolean authorize(UUID userId, String destination) {
        return supports(destination) && OwnTopic.isOwnedBy(LiveDestinations.PREFIX, destination, userId);
    }
}
