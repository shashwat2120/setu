package com.setu.directory.application;

import com.setu.directory.domain.FacultyProfile;
import com.setu.directory.domain.FacultyStatus;
import com.setu.directory.infrastructure.FacultyProfileInsertGuard;
import com.setu.directory.infrastructure.FacultyProfileRepository;
import com.setu.shared.identity.PersonDirectory;
import com.setu.shared.directory.FacultyDirectory;
import com.setu.shared.authorization.AuthorizationContext;
import com.setu.shared.authorization.ResourceRef;
import com.setu.shared.governance.AuditLogWriter;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Faculty profile CRUD (Day 6 §4). No cataloged Kafka event exists for
 * faculty onboarding (docs/kafka-event-catalog.md has no FacultyCreated/
 * FacultyOnboarded row), so none is invented here — only
 * {@code governance.audit_logs}, which Day 6 §10 explicitly requires.
 * <p>
 * Directory's implementation of the shared {@link FacultyDirectory} port
 * (Day 7): {@code enrollment.application.FacultyAssignmentService} needs a
 * faculty id's tenant to validate an assignment — that ONE method
 * ({@link #findTenantId}) is reached only through
 * {@code com.setu.shared.directory.FacultyDirectory}, never this concrete
 * class, for the cycle-avoidance reasons in {@code shared.directory}'s
 * package-info.
 * <p>
 * {@code @NamedInterface} added at the class level (Day 9 §15 ceremony),
 * separately from the above: {@code attendance.application} needs
 * {@link #findIdByTenantAndOwnerUserId} to resolve which faculty is acting,
 * from the authenticated principal, for session open/close/correction
 * authorization, and the WebSocket subscription authorizer needs the same
 * lookup. A different caller, a different method, no reverse edge from
 * directory into attendance — the same plain-{@code @NamedInterface}
 * pattern already used by {@code catalog.AcademicPeriodService} for an
 * analogous two-consumer situation. Verified via {@code ModularityTests}.
 */
@org.springframework.modulith.NamedInterface
@Service
public class FacultyProfileService implements FacultyDirectory {

    private static final String MANAGE_PERMISSION = "MANAGE_FACULTY";
    private static final String ADMIN_VIEW_PERMISSION = "VIEW_TENANT_DATA";

    private final FacultyProfileRepository facultyProfileRepository;
    private final FacultyProfileInsertGuard facultyProfileInsertGuard;
    private final PersonDirectory personDirectory;
    private final AuthorizationContext authorizationContext;
    private final AuditLogWriter auditLogWriter;

    public FacultyProfileService(FacultyProfileRepository facultyProfileRepository,
                                  FacultyProfileInsertGuard facultyProfileInsertGuard, PersonDirectory personDirectory,
                                  AuthorizationContext authorizationContext, AuditLogWriter auditLogWriter) {
        this.facultyProfileRepository = facultyProfileRepository;
        this.facultyProfileInsertGuard = facultyProfileInsertGuard;
        this.personDirectory = personDirectory;
        this.authorizationContext = authorizationContext;
        this.auditLogWriter = auditLogWriter;
    }

    public sealed interface CreateResult {
        record Success(UUID facultyId) implements CreateResult {
        }

        record PersonNotFound() implements CreateResult {
        }

        record PersonAlreadyHasProfileInTenant() implements CreateResult {
        }

        record DuplicateEmployeeNo() implements CreateResult {
        }
    }

    @Transactional
    public CreateResult create(UUID tenantId, UUID personId, String employeeNo, String designation,
                                LocalDate joinedAt) {
        authorizationContext.assertCan(MANAGE_PERMISSION, ResourceRef.of("faculty_profile", UUID.randomUUID(), tenantId));

        if (personDirectory.findById(personId).isEmpty()) {
            return new CreateResult.PersonNotFound();
        }
        if (facultyProfileRepository.existsByTenantIdAndPersonId(tenantId, personId)) {
            return new CreateResult.PersonAlreadyHasProfileInTenant();
        }

        UUID actor = authorizationContext.currentUserId();
        FacultyProfile faculty = new FacultyProfile(UUID.randomUUID(), tenantId, personId, employeeNo, designation,
            joinedAt, actor, Instant.now());
        try {
            facultyProfileInsertGuard.insert(faculty);
        } catch (DataIntegrityViolationException e) {
            return new CreateResult.DuplicateEmployeeNo();
        }
        auditLogWriter.write(tenantId, actor, "FACULTY_CREATED", "directory.faculty_profiles", faculty.id(),
            Map.of("employeeNo", employeeNo, "personId", personId));
        return new CreateResult.Success(faculty.id());
    }

    /** Flat cross-module result for onboarding orchestration; keeps the module boundary free of nested result types. */
    @Transactional
    public Optional<UUID> createIfPossible(UUID tenantId, UUID personId, String employeeNo, String designation, LocalDate joinedAt) {
        return switch (create(tenantId, personId, employeeNo, designation, joinedAt)) {
            case CreateResult.Success success -> Optional.of(success.facultyId());
            default -> Optional.empty();
        };
    }

    @Transactional(readOnly = true)
    public Optional<FacultyProfile> get(UUID facultyId) {
        Optional<FacultyProfile> maybeFaculty = facultyProfileRepository.findById(facultyId);
        if (maybeFaculty.isEmpty()) {
            return Optional.empty();
        }
        FacultyProfile faculty = maybeFaculty.get();
        UUID ownerUserId = personDirectory.findById(faculty.personId()).map(PersonDirectory.PersonSummary::userId).orElse(null);
        UUID currentUserId = authorizationContext.currentUserId();
        boolean isSelf = ownerUserId != null && ownerUserId.equals(currentUserId);
        if (!isSelf) {
            authorizationContext.assertCan(ADMIN_VIEW_PERMISSION, ResourceRef.of("faculty_profile", faculty.id(), faculty.tenantId()));
        }
        return Optional.of(faculty);
    }

    /** Day 16 §admin: tenant-wide listing for the College Admin faculty table — admin-only (VIEW_TENANT_DATA), never a self-view path. */
    @Transactional(readOnly = true)
    public Page<FacultyProfile> listForTenant(UUID tenantId, Pageable pageable) {
        authorizationContext.assertCan(ADMIN_VIEW_PERMISSION, ResourceRef.of("faculty_profile", UUID.randomUUID(), tenantId));
        return facultyProfileRepository.findByTenantId(tenantId, pageable);
    }

    public enum StatusChangeResult {SUCCESS, NOT_FOUND, INVALID_TRANSITION}

    @Transactional
    public StatusChangeResult changeStatus(UUID facultyId, FacultyStatus target) {
        Optional<FacultyProfile> maybeFaculty = facultyProfileRepository.findById(facultyId);
        if (maybeFaculty.isEmpty()) {
            return StatusChangeResult.NOT_FOUND;
        }
        FacultyProfile faculty = maybeFaculty.get();
        authorizationContext.assertCan(MANAGE_PERMISSION, ResourceRef.of("faculty_profile", faculty.id(), faculty.tenantId()));
        if (!faculty.canTransitionTo(target)) {
            return StatusChangeResult.INVALID_TRANSITION;
        }
        UUID actor = authorizationContext.currentUserId();
        var from = faculty.status();
        faculty.transitionTo(target, actor, Instant.now());
        facultyProfileRepository.save(faculty);
        auditLogWriter.write(faculty.tenantId(), actor, "FACULTY_STATUS_CHANGED", "directory.faculty_profiles",
            faculty.id(), Map.of("from", from.name(), "to", target.name()));
        return StatusChangeResult.SUCCESS;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UUID> findTenantId(UUID facultyId) {
        return facultyProfileRepository.findById(facultyId).map(FacultyProfile::tenantId);
    }

    /** See this class's javadoc for the full Day 9 §15 cross-module justification. */
    @Transactional(readOnly = true)
    public Optional<UUID> findIdByTenantAndOwnerUserId(UUID tenantId, UUID userId) {
        return personDirectory.findByUserId(userId)
            .flatMap(person -> facultyProfileRepository.findByTenantIdAndPersonId(tenantId, person.id()))
            .map(FacultyProfile::id);
    }

    /** Day 15 §demo-data: plain-UUID unwrap of {@link #create}. */
    @Transactional
    public UUID createForSeed(UUID tenantId, UUID personId, String employeeNo, String designation, java.time.LocalDate joinedAt) {
        return switch (create(tenantId, personId, employeeNo, designation, joinedAt)) {
            case CreateResult.Success s -> s.facultyId();
            default -> throw new IllegalStateException("seed faculty profile creation failed: " + employeeNo);
        };
    }

}
