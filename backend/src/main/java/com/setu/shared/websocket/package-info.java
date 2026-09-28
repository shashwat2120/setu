/**
 * The {@link com.setu.shared.websocket.StompDestinationAuthorizer} contract.
 * See the parent {@code shared} package's javadoc for why this lives here
 * rather than being module-owned.
 * <p>
 * Added Sprint 5D Day 9 to avoid a genuine module cycle: {@code notification}
 * owns the generic WebSocket/STOMP transport (TRD §2's notifications,
 * delivery tracking, and realtime WebSocket fan-out list), but per-destination
 * SUBSCRIBE authorization for an attendance live board requires
 * attendance-specific knowledge (is this principal the assigned faculty for
 * THIS section) that notification has no business owning. Having
 * notification's channel interceptor call
 * {@code attendance.application.AttendanceSubscriptionAuthorizer} directly
 * would create a notification&nbsp;-&gt;&nbsp;attendance edge; attendance
 * separately needs {@code notification.application.RealtimeBroadcastService}
 * to push events (attendance&nbsp;-&gt;&nbsp;notification) — together those
 * two direct edges would close a cycle. Routing subscription authorization
 * through this shared contract instead — implemented by attendance,
 * discovered as a list by notification's channel interceptor — keeps the
 * dependency graph a DAG (attendance -&gt; notification for broadcast,
 * attendance -&gt; shared &lt;- notification for authorization), the same fix
 * already applied to {@link com.setu.shared.directory.FacultyDirectory} and
 * {@link com.setu.shared.enrollment.CourseSectionDirectory} for analogous
 * cases.
 */
package com.setu.shared.websocket;
