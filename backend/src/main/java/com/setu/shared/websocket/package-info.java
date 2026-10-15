/**
 * The {@link com.setu.shared.websocket.StompDestinationAuthorizer} contract.
 * See the parent {@code shared} package's javadoc for why this lives here
 * rather than being module-owned.
 * <p>
 * Added Sprint 5D Day 9 to avoid a module cycle: notification owns the generic WebSocket/STOMP transport, but per-destination SUBSCRIBE
 * authorization for an attendance live board needs attendance-specific knowledge (is this principal the assigned faculty for THIS section).
 * Routing it through this shared contract, implemented by attendance and discovered as a list by notification's channel interceptor,
 * keeps notification from depending on attendance's classes.
 * <p>
 * Sprint 6 coupling audit: the other direction, attendance pushing live updates, is now an in-process application event
 * ({@link com.setu.shared.websocket.LiveBroadcastRequest}) that notification listens for, so attendance no longer depends on notification at
 * all and the two will not call each other once extracted (docs/extraction-audit.md).
 */
package com.setu.shared.websocket;
