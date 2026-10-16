package com.setu.notification.infrastructure.websocket;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A notification is addressed to one account, so its live topic is that account's alone: not an administrator's, not a guardian's, not
 * another student's. The destination is parsed, never trusted.
 */
class NotificationSubscriptionAuthorizerTests {

    private final NotificationSubscriptionAuthorizer authorizer = new NotificationSubscriptionAuthorizer();
    private final UUID me = UUID.randomUUID();

    @Test
    void ownsOnlyTheNotificationTopics() {
        assertThat(authorizer.supports("/topic/notifications/" + me)).isTrue();
        assertThat(authorizer.supports("/topic/notifications/")).isTrue();
        assertThat(authorizer.supports("/topic/attendance/section/" + me)).isFalse();
        assertThat(authorizer.supports(null)).isFalse();
    }

    @Test
    void aUserMayListenToTheirOwnTopicOnly() {
        assertThat(authorizer.authorize(me, "/topic/notifications/" + me)).isTrue();
        assertThat(authorizer.authorize(me, "/topic/notifications/" + me.toString().toUpperCase())).as("same id, other case").isTrue();
        assertThat(authorizer.authorize(me, "/topic/notifications/" + UUID.randomUUID())).as("another user's topic").isFalse();
    }

    @Test
    void anythingThatIsNotExactlyOneWellFormedUserIdIsRefused() {
        for (String destination : new String[] {
            "/topic/notifications/", "/topic/notifications/*", "/topic/notifications/**", "/topic/notifications/#",
            "/topic/notifications/" + me + "/extra", "/topic/notifications/" + me + " ", "/topic/notifications//" + me,
            "/topic/notifications/1-1-1-1-1", "/topic/notifications/not-a-uuid", "/topic/notificationsX/" + me, "/topic/notifications/%2A"}) {
            assertThat(authorizer.authorize(me, destination)).as(destination).isFalse();
        }
        assertThat(authorizer.authorize(null, "/topic/notifications/" + me)).as("no resolved user").isFalse();
    }
}
