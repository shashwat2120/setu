package com.setu.notification.application;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

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
@org.springframework.modulith.NamedInterface
@Service
public class RealtimeBroadcastService {

    private final SimpMessagingTemplate messagingTemplate;

    public RealtimeBroadcastService(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    public void broadcast(String destination, Object payload) {
        messagingTemplate.convertAndSend(destination, payload);
    }
}
