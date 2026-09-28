package com.setu.shared.identity;

import java.util.UUID;

/** Cross-module "does this user hold any active role in this tenant" check — a shared contract rather than a direct dependency on identity.application.UserDirectoryService, needed by directory.application.StudentProfileService without creating a directory-&gt;identity module cycle (identity already depends on directory via CurrentUserController/GuardianDelegationAdapter). */
public interface TenantMembership {

    boolean hasActiveRoleInTenant(UUID userId, UUID tenantId);

    /** Same check, but false for a user whose only active role assignment in the tenant is GUARDIAN — see UserDirectoryService's javadoc. */
    boolean hasActiveNonGuardianRoleInTenant(UUID userId, UUID tenantId);
}
