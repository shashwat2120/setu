package com.setu.shared.governance;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * Writes one append-only row to governance.audit_logs. Used directly (not
 * via an exception path) so a failed-login audit entry and its accompanying
 * lockout-counter update commit together even when the calling service
 * ultimately reports the attempt as a failure to its caller — callers should
 * return a typed result rather than throw for expected failure cases (wrong
 * password, locked account), so this write is never undone by a rollback.
 */
@Component
public class AuditLogWriter {

    private final AuditLogRepository repository;
    private final ObjectMapper objectMapper;

    public AuditLogWriter(AuditLogRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    public void write(UUID tenantId, UUID actorUserId, String action, String resourceType, UUID resourceId,
                       Object afterDetails) {
        String afterJson = afterDetails == null ? null : toJson(afterDetails);
        repository.save(new AuditLogEntry(
            UUID.randomUUID(), tenantId, actorUserId, action, resourceType, resourceId, null, afterJson,
            Instant.now()));
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{\"serializationError\":\"" + e.getMessage() + "\"}";
        }
    }
}
