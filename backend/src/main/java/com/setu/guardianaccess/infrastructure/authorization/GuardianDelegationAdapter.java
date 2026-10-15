package com.setu.guardianaccess.infrastructure.authorization;

import com.setu.directory.application.DirectoryService;
import com.setu.guardianaccess.application.GuardianAccessService;
import com.setu.shared.authorization.GuardianDelegationChecker;
import com.setu.shared.identity.PersonDirectory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * Answers one question for the authorization engine: can this authenticated user (identity.users.id), acting as a guardian, see this
 * student's data under an active, unexpired, un-revoked grant for this permission?
 * <p>
 * Lives here, with the grants it reads, and implements the shared {@link GuardianDelegationChecker} contract that identity's
 * AuthorizationService calls. It used to live in identity, which made identity depend on directory and guardianaccess and, once services are
 * extracted, the Identity service on the Academic service (a cycle, because Academic already depends on Identity). Found by the Sprint 6
 * coupling audit (docs/extraction-audit.md). The person is resolved through the shared {@link PersonDirectory} contract, not identity's
 * repository.
 */
@Component
public class GuardianDelegationAdapter implements GuardianDelegationChecker {

    private final PersonDirectory personDirectory;
    private final DirectoryService directoryService;
    private final GuardianAccessService guardianAccessService;

    public GuardianDelegationAdapter(PersonDirectory personDirectory, DirectoryService directoryService,
                                      GuardianAccessService guardianAccessService) {
        this.personDirectory = personDirectory;
        this.directoryService = directoryService;
        this.guardianAccessService = guardianAccessService;
    }

    @Override
    public boolean hasActiveDelegatedAccess(UUID guardianUserId, UUID studentProfileId, UUID tenantId,
                                             String permissionCode, Instant now) {
        var person = personDirectory.findByUserId(guardianUserId);
        if (person.isEmpty()) {
            return false;
        }
        var guardianId = directoryService.findGuardianId(person.get().id(), tenantId);
        if (guardianId.isEmpty()) {
            return false;
        }
        return guardianAccessService.hasActiveDelegatedAccess(
            guardianId.get(), studentProfileId, tenantId, permissionCode, now);
    }
}
