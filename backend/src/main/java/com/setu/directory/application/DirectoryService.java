package com.setu.directory.application;

import com.setu.directory.domain.Guardian;
import com.setu.directory.domain.StudentProfile;
import com.setu.directory.infrastructure.GuardianRepository;
import com.setu.directory.infrastructure.StudentProfileRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Directory module's public port. Day 4 scope: resolve a person to their
 * directory.guardians row (needed by guardian delegation authorization —
 * see identity.infrastructure.authorization.GuardianDelegationAdapter) and
 * provision minimal student/guardian profile rows. Full directory
 * management (profile fields, status transitions) is a later sprint's work.
 * <p>
 * {@code @NamedInterface} is applied to THIS CLASS specifically (Day 5
 * instruction §13: "do not expose whole application modules unnecessarily
 * through @NamedInterface. Only intentionally public application contracts
 * should be exposed.") — not to the {@code application} package as a
 * whole, so other classes added to this package later stay internal by
 * default unless they're deliberately exposed the same way.
 */
@org.springframework.modulith.NamedInterface
@Service
public class DirectoryService {

    private final GuardianRepository guardianRepository;
    private final StudentProfileRepository studentProfileRepository;

    public DirectoryService(GuardianRepository guardianRepository, StudentProfileRepository studentProfileRepository) {
        this.guardianRepository = guardianRepository;
        this.studentProfileRepository = studentProfileRepository;
    }

    public Optional<UUID> findGuardianId(UUID personId, UUID tenantId) {
        return guardianRepository.findByPersonIdAndTenantId(personId, tenantId).map(Guardian::id);
    }

    @Transactional
    public UUID provisionGuardian(UUID tenantId, UUID personId, UUID actorUserId) {
        Guardian guardian = new Guardian(UUID.randomUUID(), tenantId, personId, actorUserId, Instant.now());
        guardianRepository.save(guardian);
        return guardian.id();
    }

    @Transactional
    public UUID provisionStudentProfile(UUID tenantId, UUID personId, String admissionNo, UUID actorUserId) {
        Instant now = Instant.now();
        StudentProfile profile = new StudentProfile(UUID.randomUUID(), tenantId, personId, admissionNo, null,
            now.atZone(java.time.ZoneOffset.UTC).toLocalDate(), actorUserId, now);
        studentProfileRepository.save(profile);
        return profile.id();
    }
}
