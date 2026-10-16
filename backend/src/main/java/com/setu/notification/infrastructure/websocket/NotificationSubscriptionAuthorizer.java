package com.setu.notification.infrastructure.websocket;

import com.setu.notification.application.LiveDestinations;
import com.setu.shared.websocket.StompDestinationAuthorizer;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Who may listen to {@code /topic/notifications/<userId>}: that user, and nobody else, not an administrator and not the user's guardian.
 * A notification is addressed to one account, so the check is identity, not a permission. The destination is parsed, not trusted: anything
 * under the prefix that is not exactly one well-formed user id (a wildcard, a path with extra segments, another id) is refused.
 */
@Component
public class NotificationSubscriptionAuthorizer implements StompDestinationAuthorizer {

    @Override
    public boolean supports(String destination) {
        return destination != null && destination.startsWith(LiveDestinations.PREFIX);
    }

    @Override
    public boolean authorize(UUID userId, String destination) {
        if (userId == null || !supports(destination)) {
            return false;
        }
        String owner = destination.substring(LiveDestinations.PREFIX.length());
        try {
            // UUID.fromString accepts shortened forms ("1-1-1-1-1"); only the canonical rendering of the user's own id is theirs
            return UUID.fromString(owner).equals(userId) && owner.equalsIgnoreCase(userId.toString());
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
