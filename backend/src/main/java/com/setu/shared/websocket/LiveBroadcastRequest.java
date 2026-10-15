package com.setu.shared.websocket;

/**
 * "Push this payload to everyone subscribed to this STOMP destination." Published by any module as an in-process application event;
 * the notification module (which owns the WebSocket transport) listens and delivers it after the publishing transaction commits.
 * <p>
 * This replaces attendance calling notification's broadcast service directly. With that call, attendance depended on notification while
 * notification already depended on attendance for destination authorization (a bidirectional dependency that would have made the two
 * services call each other once extracted); now the dependency runs one way only (found by the Sprint 6 coupling audit,
 * docs/extraction-audit.md). The payload must carry no personal data: it reaches every subscriber of the destination.
 */
public record LiveBroadcastRequest(String destination, Object payload) {
}
