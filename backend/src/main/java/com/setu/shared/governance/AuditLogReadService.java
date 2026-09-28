package com.setu.shared.governance;

import com.setu.shared.authorization.AuthorizationContext;
import com.setu.shared.authorization.ResourceRef;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Day 16 §super-admin: the read side of the append-only audit trail {@link AuditLogWriter} has always written
 * to — no caller ever needed to read one back until the Super Admin governance view. A null {@code tenantId}
 * means the platform-wide feed (every tenant), gated on {@code MANAGE_PLATFORM} since no narrower "view all
 * audit logs" permission exists; a specific {@code tenantId} is that tenant's own feed, gated on the ordinary
 * {@code VIEW_TENANT_DATA} a college admin already holds for their own tenant.
 */
@Service
public class AuditLogReadService {

    private static final String PLATFORM_VIEW_PERMISSION = "MANAGE_PLATFORM";
    private static final String TENANT_VIEW_PERMISSION = "VIEW_TENANT_DATA";

    private final AuditLogRepository repository;
    private final AuthorizationContext authorizationContext;

    public AuditLogReadService(AuditLogRepository repository, AuthorizationContext authorizationContext) {
        this.repository = repository;
        this.authorizationContext = authorizationContext;
    }

    @Transactional(readOnly = true)
    public Page<AuditLogEntry> listRecent(UUID tenantId, Pageable pageable) {
        if (tenantId == null) {
            authorizationContext.assertCan(PLATFORM_VIEW_PERMISSION, ResourceRef.of("audit_log", UUID.randomUUID(), null));
            return repository.findAllByOrderByCreatedAtDesc(pageable);
        }
        authorizationContext.assertCan(TENANT_VIEW_PERMISSION, ResourceRef.of("audit_log", UUID.randomUUID(), tenantId));
        return repository.findByTenantIdOrderByCreatedAtDesc(tenantId, pageable);
    }
}
