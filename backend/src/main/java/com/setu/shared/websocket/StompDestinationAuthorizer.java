package com.setu.shared.websocket;

import java.util.UUID;

/**
 * Per-destination STOMP SUBSCRIBE authorization contract — see package-info
 * for why this is a shared contract rather than a direct dependency.
 * Implementations are discovered as a {@code List<StompDestinationAuthorizer>}
 * by the notification module's channel interceptor; each is asked in turn
 * whether it owns a given destination, and if so, whether the connected
 * principal may subscribe to it. The destination name itself is NEVER
 * trusted as authorization — the implementation must resolve the actual
 * resource (e.g. a section) and re-run a real permission/assignment check,
 * the same discipline as every HTTP-facing authorization call in this
 * project.
 */
public interface StompDestinationAuthorizer {

    /** True if this authorizer owns/understands this destination pattern (e.g. starts with "/topic/attendance/section/"). */
    boolean supports(String destination);

    /** Only called when {@link #supports} is true. Must resolve the real resource behind the destination and authorize against it — never trust the destination string alone. */
    boolean authorize(UUID userId, String destination);
}
