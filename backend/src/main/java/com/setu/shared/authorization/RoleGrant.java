package com.setu.shared.authorization;

import java.util.UUID;

/** One active role held by an account: the role's code and the tenant it applies in ({@code null} for a platform-scoped role). */
public record RoleGrant(String roleCode, UUID tenantId) {

    public static final String SUPER_ADMIN = "SUPER_ADMIN";
    public static final String GUARDIAN = "GUARDIAN";
}
