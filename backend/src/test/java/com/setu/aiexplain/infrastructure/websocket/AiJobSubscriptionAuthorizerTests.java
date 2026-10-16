package com.setu.aiexplain.infrastructure.websocket;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A job's status changes are pushed to {@code /topic/ai/jobs/<userId>}. Whose job it is is the whole authorization question, so the topic is its
 * owner's alone, and the destination is parsed, never trusted.
 */
class AiJobSubscriptionAuthorizerTests {

    private final AiJobSubscriptionAuthorizer authorizer = new AiJobSubscriptionAuthorizer();
    private final UUID me = UUID.randomUUID();

    @Test
    void ownsOnlyTheJobTopics() {
        assertThat(authorizer.supports("/topic/ai/jobs/" + me)).isTrue();
        assertThat(authorizer.supports("/topic/notifications/" + me)).as("that is the notification service's").isFalse();
        assertThat(authorizer.supports("/topic/attendance/section/" + me)).isFalse();
        assertThat(authorizer.supports(null)).isFalse();
    }

    @Test
    void aUserMayListenToTheirOwnJobsOnly() {
        assertThat(authorizer.authorize(me, "/topic/ai/jobs/" + me)).isTrue();
        assertThat(authorizer.authorize(me, "/topic/ai/jobs/" + UUID.randomUUID())).isFalse();
    }

    @Test
    void anythingThatIsNotExactlyOneWellFormedUserIdIsRefused() {
        for (String destination : new String[] {"/topic/ai/jobs/", "/topic/ai/jobs/*", "/topic/ai/jobs/**", "/topic/ai/jobs/" + me + "/x",
            "/topic/ai/jobs/ " + me, "/topic/ai/jobs/1-1-1-1-1", "/topic/ai/jobsX/" + me, "/topic/ai/jobs//" + me}) {
            assertThat(authorizer.authorize(me, destination)).as(destination).isFalse();
        }
        assertThat(authorizer.authorize(null, "/topic/ai/jobs/" + me)).isFalse();
    }
}
