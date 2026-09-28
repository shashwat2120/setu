package com.setu.institution.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Maps institution.policy_documents (V70) — see that migration's header
 * comment for the full rationale on why institution owns this rather than
 * a new ai_explain schema. Append-only for Day 14 (no update/delete yet — a disclosed
 * limitation, not an oversight: policy content changing over time is a
 * real future need, just not one Day 14's AI-integration scope requires).
 * {@code requiredPermissionCode} is the document-ACL gate — see {@link
 * com.setu.institution.application.PolicyDocumentService}.
 */
@Entity
@Table(name = "policy_documents", schema = "institution")
public class PolicyDocument {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private String content;

    @Column(name = "required_permission_code")
    private String requiredPermissionCode;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    protected PolicyDocument() {
    }

    public PolicyDocument(UUID id, UUID tenantId, String title, String content, String requiredPermissionCode,
                           UUID createdBy, Instant createdAt) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title must not be blank");
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("content must not be blank");
        }
        this.id = id;
        this.tenantId = tenantId;
        this.title = title;
        this.content = content;
        this.requiredPermissionCode = requiredPermissionCode;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
    }

    public UUID id() {
        return id;
    }

    public UUID tenantId() {
        return tenantId;
    }

    public String title() {
        return title;
    }

    public String content() {
        return content;
    }

    public String requiredPermissionCode() {
        return requiredPermissionCode;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
