package com.setu.career.application;

import com.setu.directory.application.StudentProfileService;
import com.setu.shared.authorization.AuthorizationContext;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Intra-module helper shared by every {@code career.application} service —
 * package-private, never exposed cross-module. Same shape as {@code
 * assessment.application.AssessmentAccessSupport}/{@code
 * finance.application.FinanceAccessSupport}: each module keeps its own
 * small access-support helper rather than sharing one across module
 * boundaries, the established precedent throughout this codebase.
 */
@Component
class CareerAccessSupport {

    private final StudentProfileService studentProfileService;
    private final AuthorizationContext authorizationContext;

    CareerAccessSupport(StudentProfileService studentProfileService, AuthorizationContext authorizationContext) {
        this.studentProfileService = studentProfileService;
        this.authorizationContext = authorizationContext;
    }

    /** The current principal's own student-profile id in this tenant, if any. */
    Optional<UUID> currentStudentId(UUID tenantId) {
        return studentProfileService.findIdByTenantAndOwnerUserId(tenantId, authorizationContext.currentUserId());
    }
}
