package com.setu.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/** Maps identity.role_permissions (V53) — a fixed, seeded catalog (V55) for Day 4; role/permission catalog management (create/edit) is not part of this Sprint. */
@Entity
@Table(name = "role_permissions", schema = "identity")
public class RolePermission {

    @Id
    private UUID id;

    @Column(name = "role_id", nullable = false)
    private UUID roleId;

    @Column(name = "permission_code", nullable = false)
    private String permissionCode;

    protected RolePermission() {
    }

    public UUID roleId() {
        return roleId;
    }

    public String permissionCode() {
        return permissionCode;
    }
}
