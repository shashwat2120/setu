package com.setu.finance.application;

import com.setu.directory.application.StudentProfileService;
import com.setu.shared.authorization.AuthorizationContext;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Intra-module helper shared by every {@code finance.application} service —
 * package-private, never exposed cross-module. Same shape as {@code
 * assessment.application.AssessmentAccessSupport}: each module keeps its
 * own small access-support helper rather than sharing one across module
 * boundaries, the established precedent throughout this codebase.
 */
@Component
class FinanceAccessSupport {

    private final StudentProfileService studentProfileService;
    private final AuthorizationContext authorizationContext;

    FinanceAccessSupport(StudentProfileService studentProfileService, AuthorizationContext authorizationContext) {
        this.studentProfileService = studentProfileService;
        this.authorizationContext = authorizationContext;
    }

    /** The current principal's own student-profile id in this tenant, if any. */
    Optional<UUID> currentStudentId(UUID tenantId) {
        return studentProfileService.findIdByTenantAndOwnerUserId(tenantId, authorizationContext.currentUserId());
    }

    /** The identity.users.id that owns a given student-profile id — needed for guardian-delegation ownership scoping (same precedent as LearningProgressService/GradeService). */
    Optional<UUID> ownerUserIdOf(UUID studentProfileId) {
        return studentProfileService.findTenantAndOwnerUserId(studentProfileId)
            .map(StudentProfileService.StudentTenantOwner::ownerUserId);
    }
}
