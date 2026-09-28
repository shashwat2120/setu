package com.setu.directory.application;

import com.setu.directory.domain.Guardian;
import com.setu.shared.identity.PersonDirectory;
import com.setu.directory.infrastructure.GuardianInsertGuard;
import com.setu.directory.infrastructure.GuardianRepository;
import com.setu.shared.authorization.AuthorizationContext;
import com.setu.shared.authorization.ResourceRef;
import com.setu.shared.governance.AuditLogWriter;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Guardian profile CRUD (Day 6 §7) — the DOMAIN record only; confers no
 * access by itself. Distinct from Day 4's DirectoryService.provisionGuardian,
 * which remains the unauthenticated bootstrap primitive used by fixtures.
 * <p>
 * {@code @NamedInterface} on this class (Day 6 §13 ceremony): caller is
 * {@code guardianaccess.application.GuardianAccessService}, which must (a)
 * validate a grant's guardian/tenant consistency and (b) resolve the
 * guardian's own {@code identity.users.id} to enforce "a guardian cannot
 * grant themselves access" (Day 6 §8). Only {@link #getSummary} is exposed
 * — a read projection ({@link GuardianSummary}), never the {@code Guardian}
 * entity. No authorization check of its own, for the same reason every
 * other narrow cross-module lookup in this codebase has none: the caller
 * has already authorized its own operation. Verified via
 * {@code ModularityTests}.
 */
@org.springframework.modulith.NamedInterface
@Service
public class GuardianProfileService {

    private static final String MANAGE_PERMISSION = "MANAGE_GUARDIANS";
    private static final String ADMIN_VIEW_PERMISSION = "VIEW_TENANT_DATA";

    private final GuardianRepository guardianRepository;
    private final GuardianInsertGuard guardianInsertGuard;
    private final PersonDirectory personDirectory;
    private final AuthorizationContext authorizationContext;
    private final AuditLogWriter auditLogWriter;

    public GuardianProfileService(GuardianRepository guardianRepository, GuardianInsertGuard guardianInsertGuard,
                                   PersonDirectory personDirectory,
                                   AuthorizationContext authorizationContext, AuditLogWriter auditLogWriter) {
        this.guardianRepository = guardianRepository;
        this.guardianInsertGuard = guardianInsertGuard;
        this.personDirectory = personDirectory;
        this.authorizationContext = authorizationContext;
        this.auditLogWriter = auditLogWriter;
    }

    public sealed interface CreateResult {
        record Success(UUID guardianId) implements CreateResult {
        }

        record PersonNotFound() implements CreateResult {
        }

        record PersonAlreadyHasProfileInTenant() implements CreateResult {
        }
    }

    @Transactional
    public CreateResult create(UUID tenantId, UUID personId, String occupation, String organization, String addressJson) {
        authorizationContext.assertCan(MANAGE_PERMISSION, ResourceRef.of("guardian", UUID.randomUUID(), tenantId));

        if (personDirectory.findById(personId).isEmpty()) {
            return new CreateResult.PersonNotFound();
        }
        if (guardianRepository.findByPersonIdAndTenantId(personId, tenantId).isPresent()) {
            return new CreateResult.PersonAlreadyHasProfileInTenant();
        }

        UUID actor = authorizationContext.currentUserId();
        Guardian guardian = new Guardian(UUID.randomUUID(), tenantId, personId, occupation, organization,
            addressJson, actor, Instant.now());
        try {
            guardianInsertGuard.insert(guardian);
        } catch (DataIntegrityViolationException e) {
            return new CreateResult.PersonAlreadyHasProfileInTenant();
        }
        auditLogWriter.write(tenantId, actor, "GUARDIAN_CREATED", "directory.guardians", guardian.id(),
            Map.of("personId", personId));
        return new CreateResult.Success(guardian.id());
    }

    /** Flat result for cross-module onboarding orchestration; no directory entity or nested result crosses the module boundary. */
    @Transactional
    public Optional<UUID> createIfPossible(UUID tenantId, UUID personId, String occupation, String organization, String addressJson) {
        return switch (create(tenantId, personId, occupation, organization, addressJson)) {
            case CreateResult.Success success -> Optional.of(success.guardianId());
            default -> Optional.empty();
        };
    }

    @Transactional(readOnly = true)
    public Optional<Guardian> get(UUID guardianId) {
        Optional<Guardian> maybeGuardian = guardianRepository.findById(guardianId);
        if (maybeGuardian.isEmpty()) {
            return Optional.empty();
        }
        Guardian guardian = maybeGuardian.get();
        UUID ownerUserId = personDirectory.findById(guardian.personId()).map(PersonDirectory.PersonSummary::userId).orElse(null);
        UUID currentUserId = authorizationContext.currentUserId();
        boolean isSelf = ownerUserId != null && ownerUserId.equals(currentUserId);
        if (!isSelf) {
            authorizationContext.assertCan(ADMIN_VIEW_PERMISSION, ResourceRef.of("guardian", guardian.id(), guardian.tenantId()));
        }
        return Optional.of(guardian);
    }

    public enum UpdateResult {SUCCESS, NOT_FOUND}

    @Transactional
    public UpdateResult update(UUID guardianId, String occupation, String organization, String addressJson) {
        Optional<Guardian> maybeGuardian = guardianRepository.findById(guardianId);
        if (maybeGuardian.isEmpty()) {
            return UpdateResult.NOT_FOUND;
        }
        Guardian guardian = maybeGuardian.get();
        authorizationContext.assertCan(MANAGE_PERMISSION, ResourceRef.of("guardian", guardian.id(), guardian.tenantId()));
        UUID actor = authorizationContext.currentUserId();
        guardian.update(occupation, organization, addressJson, actor, Instant.now());
        guardianRepository.save(guardian);
        auditLogWriter.write(guardian.tenantId(), actor, "GUARDIAN_UPDATED", "directory.guardians", guardian.id(), Map.of());
        return UpdateResult.SUCCESS;
    }

    /** See this class's javadoc for the full Day 6 §13 cross-module justification. */
    @Transactional(readOnly = true)
    public Optional<GuardianSummary> getSummary(UUID guardianId) {
        return guardianRepository.findById(guardianId).map(g -> {
            UUID ownerUserId = personDirectory.findById(g.personId()).map(PersonDirectory.PersonSummary::userId).orElse(null);
            return new GuardianSummary(g.id(), g.tenantId(), g.personId(), ownerUserId);
        });
    }

    public record GuardianSummary(UUID id, UUID tenantId, UUID personId, UUID ownerUserId) {
    }

    /** Same self-lookup idiom as StudentProfileService/FacultyProfileService's findIdByTenantAndOwnerUserId — no authorization of its own; the caller (e.g. a guardian looking up their own guardianId) authorizes by construction since it can only ever resolve the CURRENT principal's own record. */
    @Transactional(readOnly = true)
    public Optional<UUID> findIdByTenantAndOwnerUserId(UUID tenantId, UUID userId) {
        return personDirectory.findByUserId(userId)
            .flatMap(person -> guardianRepository.findByPersonIdAndTenantId(person.id(), tenantId))
            .map(Guardian::id);
    }

    /** Day 15 §demo-data: plain-UUID unwrap of {@link #create}. */
    @Transactional
    public UUID createForSeed(UUID tenantId, UUID personId, String occupation, String organization, String addressJson) {
        return switch (create(tenantId, personId, occupation, organization, addressJson)) {
            case CreateResult.Success s -> s.guardianId();
            default -> throw new IllegalStateException("seed guardian profile creation failed for person: " + personId);
        };
    }

}
