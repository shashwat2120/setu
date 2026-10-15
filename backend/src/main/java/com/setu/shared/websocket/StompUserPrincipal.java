package com.setu.shared.websocket;

import java.security.Principal;
import java.util.UUID;

/**
 * Wraps the authenticated userId resolved at STOMP CONNECT time as a {@link Principal}, the shape Spring's STOMP
 * session machinery expects. Public (not package-private) solely so integration tests can construct one directly
 * to simulate "the CONNECT phase already resolved this principal" — real WebSocket sessions still only ever get
 * one from {@code StompAuthorizationChannelInterceptor.handleConnect}; nothing about the class becomes
 * externally settable or mutable.
 */
public final class StompUserPrincipal implements Principal {

    private final UUID userId;

    public StompUserPrincipal(UUID userId) {
        this.userId = userId;
    }

    public UUID userId() {
        return userId;
    }

    @Override
    public String getName() {
        return userId.toString();
    }
}
