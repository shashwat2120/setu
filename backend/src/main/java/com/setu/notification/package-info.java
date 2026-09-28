/**
 * Notification module.
 * <p>
 * Notifications and delivery tracking, plus realtime WebSocket fan-out. Owns the notification schema.
 * Backed by the PostgreSQL 'notification' schema (see docs/schema-per-module-map.md).
 * <p>
 * Module boundary rule (TRD §2): other modules reach this one only through
 * {@code com.setu.notification.application} services, never through
 * {@code com.setu.notification.domain} or {@code com.setu.notification.infrastructure} directly.
 */
@org.springframework.modulith.ApplicationModule(
    displayName = "Notification"
)
package com.setu.notification;
