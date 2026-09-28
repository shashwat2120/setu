package com.setu.enrollment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * A deliberately minimal, read-only mapping of enrollment.classes (V26) —
 * ONLY {@code id}/{@code tenant_id}, nothing else. {@code classes} (cohort
 * identity) is NOT Day 7 scope; this exists solely so course_sections'
 * nullable {@code class_id} can be tenant-validated when a caller supplies
 * one, since "optional class must share tenant" is an explicit Day 7 rule
 * even though building actual Class CRUD/lifecycle is not. Package-private
 * on purpose — this is an internal validation detail of
 * {@code CourseSectionService}, not a public contract, and stays entirely
 * within the {@code enrollment} module (same module classes physically
 * lives in), so it raises no cross-module boundary question at all.
 */
@Entity
@Table(name = "classes", schema = "enrollment")
class ClassTenantRef {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    protected ClassTenantRef() {
    }

    UUID tenantId() {
        return tenantId;
    }
}
