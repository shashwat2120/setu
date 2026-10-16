package com.setu.notification.application;

import java.util.UUID;

/** The STOMP destination a user's own notifications are pushed to. One topic per user; the subscription check lets only that user in. */
public final class LiveDestinations {

    public static final String PREFIX = "/topic/notifications/";

    private LiveDestinations() {
    }

    public static String forUser(UUID userId) {
        return PREFIX + userId;
    }
}
