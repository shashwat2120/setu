package com.setu.guardianaccess.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/** Maps guardian_access.guardian_access_permissions (V17) — one specific permission code attached to one grant; a grant with zero rows here confers no access at all. */
@Entity
@Table(name = "guardian_access_permissions", schema = "guardian_access")
public class GuardianAccessPermission {

    @Id
    private UUID id;

    @Column(name = "grant_id", nullable = false)
    private UUID grantId;

    @Column(name = "permission_code", nullable = false)
    private String permissionCode;

    protected GuardianAccessPermission() {
    }

    public GuardianAccessPermission(UUID id, UUID grantId, String permissionCode) {
        this.id = id;
        this.grantId = grantId;
        this.permissionCode = permissionCode;
    }

    public String permissionCode() {
        return permissionCode;
    }
}
