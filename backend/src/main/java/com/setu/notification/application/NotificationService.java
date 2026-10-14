package com.setu.notification.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.setu.shared.authorization.AuthorizationContext;
import com.setu.shared.tenant.TenantContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * A person's own in-app notifications. The ownership rule is the whole authorization decision here: every query is restricted to
 * the authenticated caller's user id (read from the security context, never from a request), and, when the caller is acting inside
 * one college, to that college's rows too. A notification id that belongs to someone else is indistinguishable from one that does
 * not exist, so ids cannot be probed.
 */
@Service
public class NotificationService {

    public static final int DEFAULT_LIMIT = 20;
    public static final int MAX_LIMIT = 50;

    public record NotificationView(UUID id, String type, String title, String message, String link, Instant createdAt, Instant readAt) {
    }

    public record NotificationPage(List<NotificationView> items, long unreadCount) {
    }

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;
    private final AuthorizationContext authorizationContext;
    private final TenantContext tenantContext;

    public NotificationService(JdbcClient jdbc, ObjectMapper objectMapper, AuthorizationContext authorizationContext,
                                TenantContext tenantContext) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.authorizationContext = authorizationContext;
        this.tenantContext = tenantContext;
    }

    /** The caller's newest notifications (newest first) and how many of all of theirs are still unread. */
    @Transactional(readOnly = true)
    public NotificationPage listMine(boolean unreadOnly, int limit) {
        UUID me = authorizationContext.currentUserId();
        UUID tenant = tenantContext.tenantId().orElse(null);
        int bounded = Math.max(1, Math.min(limit, MAX_LIMIT));

        List<NotificationView> items = jdbc.sql("""
                SELECT id, type, payload_json::text AS payload, created_at, read_at
                FROM notification.notifications
                WHERE user_id = :me AND (CAST(:tenant AS uuid) IS NULL OR tenant_id = :tenant)
                  AND (:unreadOnly = false OR read_at IS NULL)
                ORDER BY created_at DESC, id DESC
                LIMIT :limit
                """)
            .param("me", me).param("tenant", tenant).param("unreadOnly", unreadOnly).param("limit", bounded)
            .query((rs, i) -> toView(rs.getObject("id", UUID.class), rs.getString("type"), rs.getString("payload"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("read_at") == null ? null : rs.getTimestamp("read_at").toInstant()))
            .list();

        long unread = jdbc.sql("""
                SELECT count(*) FROM notification.notifications
                WHERE user_id = :me AND read_at IS NULL AND (CAST(:tenant AS uuid) IS NULL OR tenant_id = :tenant)
                """)
            .param("me", me).param("tenant", tenant).query(Long.class).single();
        return new NotificationPage(items, unread);
    }

    /** Marks one of the caller's notifications read. Empty when it is not theirs or does not exist; already-read stays read. */
    @Transactional
    public Optional<Instant> markRead(UUID notificationId) {
        UUID me = authorizationContext.currentUserId();
        UUID tenant = tenantContext.tenantId().orElse(null);
        int touched = jdbc.sql("""
                UPDATE notification.notifications SET read_at = COALESCE(read_at, now())
                WHERE id = :id AND user_id = :me AND (CAST(:tenant AS uuid) IS NULL OR tenant_id = :tenant)
                """)
            .param("id", notificationId).param("me", me).param("tenant", tenant).update();
        if (touched == 0) {
            return Optional.empty();
        }
        return jdbc.sql("SELECT read_at FROM notification.notifications WHERE id = :id AND user_id = :me")
            .param("id", notificationId).param("me", me).query(java.sql.Timestamp.class).optional().map(java.sql.Timestamp::toInstant);
    }

    /** Marks all of the caller's unread notifications read; returns how many changed. */
    @Transactional
    public int markAllRead() {
        UUID me = authorizationContext.currentUserId();
        UUID tenant = tenantContext.tenantId().orElse(null);
        return jdbc.sql("""
                UPDATE notification.notifications SET read_at = now()
                WHERE user_id = :me AND read_at IS NULL AND (CAST(:tenant AS uuid) IS NULL OR tenant_id = :tenant)
                """)
            .param("me", me).param("tenant", tenant).update();
    }

    private NotificationView toView(UUID id, String type, String payloadJson, Instant createdAt, Instant readAt) {
        String title = type;
        String message = "";
        String link = null;
        if (payloadJson != null) {
            try {
                JsonNode node = objectMapper.readTree(payloadJson);
                title = text(node, "title", type);
                message = text(node, "message", "");
                String candidate = text(node, "link", null);
                // Only in-app paths are ever handed to the browser as a link target.
                link = candidate != null && candidate.startsWith("/app/") && !candidate.startsWith("//") ? candidate : null;
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                // A malformed body must not hide the notification; it shows with its type as the title.
            }
        }
        return new NotificationView(id, type, title, message, link, createdAt, readAt);
    }

    private static String text(JsonNode node, String field, String fallback) {
        JsonNode n = node.get(field);
        return n == null || n.isNull() || !n.isTextual() ? fallback : n.asText();
    }
}
