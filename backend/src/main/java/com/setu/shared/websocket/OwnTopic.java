package com.setu.shared.websocket;

import java.util.UUID;

/**
 * The rule for a topic that belongs to one account, {@code <prefix><userId>}: only that account may subscribe to it. The destination is parsed,
 * never trusted. Anything under the prefix that is not exactly one canonical rendering of the caller's own user id (a wildcard, an extra
 * segment, trailing space, the shortened forms {@link UUID#fromString} tolerates, another user's id) is refused.
 */
public final class OwnTopic {

    private OwnTopic() {
    }

    public static boolean isOwnedBy(String prefix, String destination, UUID userId) {
        if (userId == null || destination == null || !destination.startsWith(prefix)) {
            return false;
        }
        String owner = destination.substring(prefix.length());
        try {
            return UUID.fromString(owner).equals(userId) && owner.equalsIgnoreCase(userId.toString());
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
