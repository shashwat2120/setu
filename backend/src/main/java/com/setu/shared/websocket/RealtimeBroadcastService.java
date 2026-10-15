package com.setu.shared.websocket;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Day 9 §9 cross-module port: pushes a payload to a STOMP destination.
 * Deliberately generic — knows nothing about attendance or any other
 * business event; the caller decides the destination and the (already
 * PII-scrubbed) payload shape.
 * <p>
 * {@code @NamedInterface} on this class (Day 9 §15 ceremony): caller is
 * {@code attendance.application}, which broadcasts an updated
 * count/status to a section's live board immediately after an attendance
 * record commits. Only {@link #broadcast} is exposed. No reverse edge from
 * notification into attendance exists (subscription authorization is
 * routed through {@code shared.websocket.StompDestinationAuthorizer}
 * instead — see that package's javadoc). Verified via
 * {@code ModularityTests}.
 */
@Service
@ConditionalOnProperty(name = "app.realtime.enabled", havingValue = "true", matchIfMissing = true)
public class RealtimeBroadcastService {

    private final SimpMessagingTemplate messagingTemplate;

    public RealtimeBroadcastService(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    /**
     * Delivers a {@link LiveBroadcastRequest} published by another module. After the publishing transaction commits, so subscribers never
     * see an update for something that was then rolled back; at once when published outside a transaction.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    void onLiveBroadcastRequest(LiveBroadcastRequest request) {
        broadcast(request.destination(), request.payload());
    }

    public void broadcast(String destination, Object payload) {
        messagingTemplate.convertAndSend(destination, payload);
    }
}
