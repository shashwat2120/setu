package com.setu.shared.directory;

import java.util.Optional;
import java.util.UUID;

/** Cross-module faculty tenant lookup used by enrollment.FacultyAssignmentService — see package-info for why this is a shared contract rather than a direct dependency on directory.application.FacultyProfileService. */
public interface FacultyDirectory {

    Optional<UUID> findTenantId(UUID facultyId);

    /** Day 16 §faculty: resolves "which faculty profile does the CURRENT caller own" — used by {@code FacultyAssignmentService#listForFaculty} to let a faculty member see their own assigned sections without a separate admin permission. */
    Optional<UUID> findIdByTenantAndOwnerUserId(UUID tenantId, UUID userId);
}
