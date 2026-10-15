package com.setu.directory.infrastructure.web;

import com.setu.directory.application.FacultyProfileService;
import com.setu.directory.application.GuardianProfileService;
import com.setu.directory.application.StudentProfileService;
import com.setu.shared.authorization.AuthorizationContext;
import com.setu.shared.identity.TenantMembership;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Which student, faculty and guardian profiles the signed-in person holds in a college. Self-only: the profiles returned are always the
 * caller's own, found from the authenticated user, never from anything in the request; the {@code tenantId} only selects which college's
 * profiles, and is refused unless the caller holds an active role there. Profile ids are Academic-service data, so they are served here
 * and not by identity's {@code /identity/me} (see CurrentUserController and docs/extraction-audit.md).
 */
@RestController
@RequestMapping("/directory/me")
public class DirectoryMeController {

    private final StudentProfileService studentProfileService;
    private final FacultyProfileService facultyProfileService;
    private final GuardianProfileService guardianProfileService;
    private final TenantMembership tenantMembership;
    private final AuthorizationContext authorizationContext;

    public DirectoryMeController(StudentProfileService studentProfileService, FacultyProfileService facultyProfileService,
                                  GuardianProfileService guardianProfileService, TenantMembership tenantMembership,
                                  AuthorizationContext authorizationContext) {
        this.studentProfileService = studentProfileService;
        this.facultyProfileService = facultyProfileService;
        this.guardianProfileService = guardianProfileService;
        this.tenantMembership = tenantMembership;
        this.authorizationContext = authorizationContext;
    }

    public record ProfilesResponse(UUID studentId, UUID facultyId, UUID guardianId) {
    }

    @GetMapping("/profiles")
    public ProfilesResponse profiles(@RequestParam UUID tenantId) {
        UUID userId = authorizationContext.currentUserId();
        if (!tenantMembership.hasActiveRoleInTenant(userId, tenantId)) {
            throw new AccessDeniedException("no active role in this college");
        }
        return new ProfilesResponse(
            studentProfileService.findIdByTenantAndOwnerUserId(tenantId, userId).orElse(null),
            facultyProfileService.findIdByTenantAndOwnerUserId(tenantId, userId).orElse(null),
            guardianProfileService.findIdByTenantAndOwnerUserId(tenantId, userId).orElse(null));
    }
}
