package com.setu.identity.domain;

/** Matches identity.roles.scope_type's CHECK constraint (V8). PLATFORM roles (SUPER_ADMIN) grant no tenant; TENANT roles must be paired with a tenant_id in user_roles. */
public enum RoleScope {
    PLATFORM, TENANT
}
