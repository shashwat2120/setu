package com.setu.shared.websocket;

import java.util.Optional;
import java.util.UUID;

/**
 * Who is connecting? A STOMP CONNECT frame carries a bearer token, and WebSocket handshakes never pass through the HTTP security filter chain,
 * so the channel interceptor asks this port. In the monolith Identity answers (it also checks the login is still live); in an extracted
 * service the answer comes from the published keys and the revocation list ({@link RemoteStompTokenAuthenticator}). Returns only the user id,
 * never the claims or the token.
 */
public interface StompTokenAuthenticator {

    /** The user the token authenticates, or empty for a token that is missing, forged, expired or revoked. */
    Optional<UUID> resolveUserId(String bearerToken);
}
