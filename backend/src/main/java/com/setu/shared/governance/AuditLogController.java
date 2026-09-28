package com.setu.shared.governance;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Day 16 §super-admin: read-only governance view over the append-only audit trail — see {@link AuditLogReadService}'s javadoc. */
@RestController
@RequestMapping("/governance/audit-log")
public class AuditLogController {

    private final AuditLogReadService auditLogReadService;

    public AuditLogController(AuditLogReadService auditLogReadService) {
        this.auditLogReadService = auditLogReadService;
    }

    public record EntryResponse(UUID id, UUID tenantId, UUID actorUserId, String action, String resourceType,
                                 UUID resourceId, Instant createdAt) {
    }

    public record PageResponse(List<EntryResponse> content, int page, int size, long totalElements, int totalPages) {
    }

    private static EntryResponse toResponse(AuditLogEntry e) {
        return new EntryResponse(e.id(), e.tenantId(), e.actorUserId(), e.action(), e.resourceType(), e.resourceId(), e.createdAt());
    }

    @GetMapping
    public ResponseEntity<PageResponse> list(@RequestParam(required = false) UUID tenantId,
                                              @RequestParam(defaultValue = "0") int page,
                                              @RequestParam(defaultValue = "20") int size) {
        Page<AuditLogEntry> result = auditLogReadService.listRecent(tenantId, PageRequest.of(page, size));
        return ResponseEntity.ok(new PageResponse(result.getContent().stream().map(AuditLogController::toResponse).toList(),
            result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages()));
    }
}
