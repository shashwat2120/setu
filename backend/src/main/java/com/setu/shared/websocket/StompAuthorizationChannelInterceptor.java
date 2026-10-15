package com.setu.shared.websocket;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.jspecify.annotations.NonNull;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Day 9 §8-9: authenticates STOMP CONNECT frames (the WebSocket handshake
 * itself carries no HTTP Authorization header the servlet filter chain
 * would see — CONNECT is the first point a bearer token can be checked) and
 * authorizes every SUBSCRIBE frame's destination before Spring lets the
 * subscription through. Destination names are NEVER trusted as
 * authorization by themselves (Day 9 §8 explicit instruction) — every
 * {@link StompDestinationAuthorizer} that claims a destination re-resolves
 * the real resource behind it and re-checks a real permission/assignment,
 * the same discipline as any HTTP endpoint. A destination no injected
 * authorizer claims is allowed through unauthenticated-principal-only
 * (any connected, authenticated user) — there are currently none of those,
 * but future non-section-scoped notification destinations may exist.
 */
@Component
@ConditionalOnProperty(name = "app.realtime.enabled", havingValue = "true", matchIfMissing = true)
public class StompAuthorizationChannelInterceptor implements ChannelInterceptor {

    private final StompTokenAuthenticator tokenAuthenticator;
    private final List<StompDestinationAuthorizer> authorizers;

    public StompAuthorizationChannelInterceptor(StompTokenAuthenticator tokenAuthenticator,
                                                  List<StompDestinationAuthorizer> authorizers) {
        this.tokenAuthenticator = tokenAuthenticator;
        this.authorizers = authorizers;
    }

    @Override
    public Message<?> preSend(@NonNull Message<?> message, @NonNull MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) {
            return message;
        }
        if (accessor.getCommand() == StompCommand.CONNECT) {
            handleConnect(accessor);
        } else if (accessor.getCommand() == StompCommand.SUBSCRIBE) {
            handleSubscribe(accessor);
        }
        return message;
    }

    private void handleConnect(StompHeaderAccessor accessor) {
        String header = accessor.getFirstNativeHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            throw new AccessDeniedException("Missing bearer token on STOMP CONNECT");
        }
        String token = header.substring("Bearer ".length());
        UUID userId = tokenAuthenticator.resolveUserId(token)
            .orElseThrow(() -> new AccessDeniedException("Invalid or expired token"));
        accessor.setUser(new StompUserPrincipal(userId));
    }

    private void handleSubscribe(StompHeaderAccessor accessor) {
        if (!(accessor.getUser() instanceof StompUserPrincipal principal)) {
            throw new AccessDeniedException("Access denied: unauthenticated");
        }
        String destination = accessor.getDestination();
        if (destination == null) {
            throw new AccessDeniedException("Missing destination");
        }
        for (StompDestinationAuthorizer authorizer : authorizers) {
            if (authorizer.supports(destination)) {
                if (!authorizer.authorize(principal.userId(), destination)) {
                    throw new AccessDeniedException("Access denied: not authorized for " + destination);
                }
                return;
            }
        }
        // No authorizer claims this destination — allowed for any authenticated principal.
    }
}
