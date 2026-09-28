package com.setu.identity.infrastructure.authorization;

import com.setu.directory.application.DirectoryService;
import com.setu.guardianaccess.application.GuardianAccessService;
import com.setu.identity.infrastructure.PersonRepository;
import com.setu.shared.authorization.GuardianDelegationChecker;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * Composes three modules to answer one question: can this authenticated
 * user (identity.users.id), acting as a guardian, see this student's data?
 * identity -&gt; persons (same module), directory -&gt; guardians (cross-module
 * application call), guardianaccess -&gt; grants+permissions (cross-module
 * application call). Lives in identity because AuthorizationService (the
 * only caller) already lives here — see shared.authorization's
 * package-info for why the composition can't live in directory or
 * guardianaccess without creating a dependency back the other way.
 */
@Component
public class GuardianDelegationAdapter implements GuardianDelegationChecker {

    private final PersonRepository personRepository;
    private final DirectoryService directoryService;
    private final GuardianAccessService guardianAccessService;

    public GuardianDelegationAdapter(PersonRepository personRepository, DirectoryService directoryService,
                                      GuardianAccessService guardianAccessService) {
        this.personRepository = personRepository;
        this.directoryService = directoryService;
        this.guardianAccessService = guardianAccessService;
    }

    @Override
    public boolean hasActiveDelegatedAccess(UUID guardianUserId, UUID studentProfileId, UUID tenantId,
                                             String permissionCode, Instant now) {
        var person = personRepository.findByUserId(guardianUserId);
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
