package com.setu.aiexplain.infrastructure.websocket;

import com.setu.aiexplain.application.AiJobService;
import com.setu.shared.websocket.OwnTopic;
import com.setu.shared.websocket.StompDestinationAuthorizer;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Who may listen to {@code /topic/ai/jobs/<userId>}: that user only. The status events say which of their jobs changed and how; the answer is read over HTTP. */
@Component
public class AiJobSubscriptionAuthorizer implements StompDestinationAuthorizer {

    private static final String PREFIX = AiJobService.TOPIC_PREFIX;

    @Override
    public boolean supports(String destination) {
        return destination != null && destination.startsWith(PREFIX);
    }

    @Override
    public boolean authorize(UUID userId, String destination) {
        return supports(destination) && OwnTopic.isOwnedBy(PREFIX, destination, userId);
    }
}
