package com.setu.directory.application;

import com.setu.shared.identity.TenantMembership;
import com.setu.directory.domain.StudentAdmittedEvent;
import com.setu.directory.domain.StudentProfile;
import com.setu.directory.domain.StudentStatus;
import com.setu.directory.infrastructure.StudentProfileInsertGuard;
import com.setu.directory.infrastructure.StudentProfileRepository;
import com.setu.shared.identity.PersonDirectory;
import com.setu.shared.authorization.AuthorizationContext;
import com.setu.shared.authorization.ResourceRef;
import com.setu.shared.governance.AuditLogWriter;
import com.setu.shared.governance.OutboxEventWriter;
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
 * Student admission/lifecycle — the real domain operation, not a blind
 * INSERT (Day 6 §3). Admission is the ONLY way a student_profiles row is
 * created (there is no pre-ACTIVE state — docs/state-machines.md #7); it
 * both persists the row and writes the {@code StudentAdmitted} outbox event
 * in the same transaction, matching the canonical commit-before-publish
 * design. No Kafka publisher is built here — outbox only (Day 6 explicit
 * instruction).
 * <p>
 * {@code @NamedInterface} on this class (Day 6 §13 ceremony): caller is
 * {@code enrollment.application.StudentProgramService}, validating a
 * student-program assignment's student against the right tenant. Only
 * {@link #findTenantId} is exposed for that — see
 * {@code institution.DepartmentService.findTenantId}'s javadoc for why this
 * kind of narrow, unauthorized-by-itself lookup is the right shape.
 * Verified via {@code ModularityTests}.
 */
@org.springframework.modulith.NamedInterface
@Service
public class StudentProfileService {

    private static final String MANAGE_PERMISSION = "MANAGE_STUDENTS";
    private static final String ADMIN_VIEW_PERMISSION = "VIEW_TENANT_DATA";

    private final StudentProfileRepository studentProfileRepository;
    private final StudentProfileInsertGuard studentProfileInsertGuard;
    private final PersonDirectory personDirectory;
    private final TenantMembership tenantMembership;
    private final AuthorizationContext authorizationContext;
    private final AuditLogWriter auditLogWriter;

    public StudentProfileService(StudentProfileRepository studentProfileRepository,
                                  StudentProfileInsertGuard studentProfileInsertGuard, PersonDirectory personDirectory,
                                  TenantMembership tenantMembership,
                                  AuthorizationContext authorizationContext, AuditLogWriter auditLogWriter,
                                  OutboxEventWriter outboxEventWriter) {
        this.studentProfileRepository = studentProfileRepository;
        this.studentProfileInsertGuard = studentProfileInsertGuard;
        this.personDirectory = personDirectory;
        this.tenantMembership = tenantMembership;
        this.authorizationContext = authorizationContext;
        this.auditLogWriter = auditLogWriter;
    }

    public sealed interface AdmitResult {
        record Success(UUID studentId) implements AdmitResult {
        }

        record PersonNotFound() implements AdmitResult {
        }

        record PersonAlreadyHasProfileInTenant() implements AdmitResult {
        }

        record DuplicateAdmissionNo() implements AdmitResult {
        }
    }

    @Transactional
    public AdmitResult admitStudent(UUID tenantId, UUID personId, String admissionNo, String rollNo,
                                     LocalDate admissionDate) {
        authorizationContext.assertCan(MANAGE_PERMISSION, ResourceRef.of("student_profile", UUID.randomUUID(), tenantId));

        if (personDirectory.findById(personId).isEmpty()) {
            return new AdmitResult.PersonNotFound();
        }
        if (studentProfileRepository.existsByTenantIdAndPersonId(tenantId, personId)) {
            return new AdmitResult.PersonAlreadyHasProfileInTenant();
        }

        UUID actor = authorizationContext.currentUserId();
        Instant now = Instant.now();
        StudentProfile profile = new StudentProfile(UUID.randomUUID(), tenantId, personId, admissionNo, rollNo,
            admissionDate, actor, now);
        var event = new StudentAdmittedEvent(UUID.randomUUID(), now, tenantId, profile.id(), UUID.randomUUID(),
            null, profile.id(), admissionNo, admissionDate);
        try {
            studentProfileInsertGuard.insertWithOutboxEvent(profile, tenantId, event);
        } catch (DataIntegrityViolationException e) {
            return new AdmitResult.DuplicateAdmissionNo();
        }

        auditLogWriter.write(tenantId, actor, "STUDENT_ADMITTED", "directory.student_profiles", profile.id(),
            Map.of("admissionNo", admissionNo, "personId", personId));
        return new AdmitResult.Success(profile.id());
    }

    /**
     * Day 16 §faculty: real gap found live — a faculty member could see a student's enrollment/grade rows for
     * their own section but not the student's basic profile (name/admission number/status), since this
     * previously allowed only the student themselves or a tenant admin. Basic profile identity fields aren't
     * as sensitive as grades/finance/risk (each separately, correctly gated elsewhere by their own
     * advisor/self checks already verified in this codebase) — any caller holding an active NON-GUARDIAN role
     * in the SAME tenant as the student may look them up, matching how a real roster lookup works.
     * <p>
     * Day 16 §guardian: real gap found live, the other direction — the roster-lookup relaxation above
     * originally used the plain {@code hasActiveRoleInTenant} check, which let a GUARDIAN see ANY student's
     * profile in their tenant merely by holding the GUARDIAN role, with no relationship or grant at all. A
     * guardian must only ever reach a student's profile through an explicit, scoped, active
     * {@code VIEW_ACADEMIC_STATUS} delegation (Day 6 §8) — never blanket tenant membership — so guardians are
     * now excluded from the roster-lookup path and routed through the same {@code delegatableForStudent} check
     * every other guardian-delegatable read in this codebase uses. The stricter admin check remains the final
     * fallback for anyone with neither a qualifying role nor a delegation.
     */
    @Transactional(readOnly = true)
    public Optional<StudentProfile> get(UUID studentProfileId) {
        Optional<StudentProfile> maybeProfile = studentProfileRepository.findById(studentProfileId);
        if (maybeProfile.isEmpty()) {
            return Optional.empty();
        }
        StudentProfile profile = maybeProfile.get();
        UUID ownerUserId = personDirectory.findById(profile.personId()).map(PersonDirectory.PersonSummary::userId).orElse(null);
        UUID currentUserId = authorizationContext.currentUserId();
        boolean isSelf = ownerUserId != null && ownerUserId.equals(currentUserId);
        if (!isSelf && !tenantMembership.hasActiveNonGuardianRoleInTenant(currentUserId, profile.tenantId())) {
            ResourceRef delegated = ResourceRef.of("student_profile", profile.id(), profile.tenantId())
                .ownedBy(ownerUserId).delegatableForStudent(profile.id());
            if (!authorizationContext.can("VIEW_ACADEMIC_STATUS", delegated).isAllowed()) {
                authorizationContext.assertCan(ADMIN_VIEW_PERMISSION, ResourceRef.of("student_profile", profile.id(), profile.tenantId()));
            }
        }
        return Optional.of(profile);
    }

    /**
     * Day 16 §admin: tenant-wide listing for the College Admin students table — never a self-view path.
     * <p>
     * Day 16 §placement: real gap found live — a placement officer reviewing applications/placements had no
     * way to resolve a studentId to a name, since this only ever accepted VIEW_TENANT_DATA (admin). Tried
     * {@code VIEW_PROFILE} first, but that permission is ALSO held by plain STUDENT and FACULTY roles (V55) —
     * wiring it in here would have let any student browse the entire tenant's roster, a real leak worse than
     * the gap it would fix. {@code MANAGE_CAREER_LISTINGS} is the correct, narrow permission instead: only
     * COLLEGE_ADMIN, PLACEMENT_OFFICER, and SUPER_ADMIN hold it (V55/V67), which is exactly the set of roles
     * that legitimately need a roster view for career-management purposes.
     */
    @Transactional(readOnly = true)
    public Page<StudentProfile> listForTenant(UUID tenantId, Pageable pageable) {
        if (!authorizationContext.can("MANAGE_CAREER_LISTINGS", ResourceRef.of("student_profile", UUID.randomUUID(), tenantId)).isAllowed()) {
            authorizationContext.assertCan(ADMIN_VIEW_PERMISSION, ResourceRef.of("student_profile", UUID.randomUUID(), tenantId));
        }
        return studentProfileRepository.findByTenantId(tenantId, pageable);
    }

    public enum StatusChangeResult {SUCCESS, NOT_FOUND, INVALID_TRANSITION}

    @Transactional
    public StatusChangeResult changeStatus(UUID studentProfileId, StudentStatus target, String reason) {
        Optional<StudentProfile> maybeProfile = studentProfileRepository.findById(studentProfileId);
        if (maybeProfile.isEmpty()) {
            return StatusChangeResult.NOT_FOUND;
        }
        StudentProfile profile = maybeProfile.get();
        authorizationContext.assertCan(MANAGE_PERMISSION, ResourceRef.of("student_profile", profile.id(), profile.tenantId()));
        if (!profile.canTransitionTo(target)) {
            return StatusChangeResult.INVALID_TRANSITION;
        }
        UUID actor = authorizationContext.currentUserId();
        var from = profile.status();
        profile.transitionTo(target, actor, Instant.now());
        studentProfileRepository.save(profile);
        auditLogWriter.write(profile.tenantId(), actor, "STUDENT_STATUS_CHANGED", "directory.student_profiles",
            profile.id(), Map.of("from", from.name(), "to", target.name(), "reason", reason == null ? "" : reason));
        return StatusChangeResult.SUCCESS;
    }

    /** Cross-module port used by enrollment.StudentProgramService to validate student/tenant consistency without exposing the StudentProfile entity itself. */
    @Transactional(readOnly = true)
    public Optional<UUID> findTenantId(UUID studentProfileId) {
        return studentProfileRepository.findById(studentProfileId).map(StudentProfile::tenantId);
    }

    /**
     * Day 8 §12 cross-module port: {@code enrollment.EnrollmentService} needs
     * to verify that a student can request enrollment only for themselves —
     * i.e. that the currently authenticated user IS the owner of the given
     * studentProfileId — without exposing the {@link StudentProfile} entity
     * or duplicating the person->user resolution this class already does in
     * {@link #get}. Bare projection, no authorization check of its own (same
     * shape/reasoning as {@link #findTenantId}).
     */
    public record StudentTenantOwner(UUID tenantId, UUID ownerUserId) {
    }

    @Transactional(readOnly = true)
    public Optional<StudentTenantOwner> findTenantAndOwnerUserId(UUID studentProfileId) {
        return studentProfileRepository.findById(studentProfileId)
            .flatMap(profile -> personDirectory.findById(profile.personId())
                .map(person -> new StudentTenantOwner(profile.tenantId(), person.userId())));
    }

    /**
     * Day 9 §4 cross-module port: the reverse lookup of
     * {@link #findTenantAndOwnerUserId} — {@code attendance.application}'s
     * scan flow resolves which student is scanning from the authenticated
     * principal alone (the scan endpoint never accepts a client-supplied
     * studentId), so it needs userId -&gt; studentProfileId within one tenant.
     */
    @Transactional(readOnly = true)
    public Optional<UUID> findIdByTenantAndOwnerUserId(UUID tenantId, UUID userId) {
        return personDirectory.findByUserId(userId)
            .flatMap(person -> studentProfileRepository.findByTenantIdAndPersonId(tenantId, person.id()))
            .map(StudentProfile::id);
    }

    /** Day 15 §demo-data: plain-UUID unwrap of {@link #admitStudent}. */
    @Transactional
    public UUID admitStudentForSeed(UUID tenantId, UUID personId, String admissionNo, String rollNo, java.time.LocalDate admissionDate) {
        return switch (admitStudent(tenantId, personId, admissionNo, rollNo, admissionDate)) {
            case AdmitResult.Success s -> s.studentId();
            default -> throw new IllegalStateException("seed student admission failed: " + admissionNo);
        };
    }

    /**
     * Flat, cross-module-safe unwrap of {@link #admitStudent} for real (non-seed) orchestration callers outside
     * this module, e.g. {@code shared.admin.StudentAdmissionService} — see {@link
     * com.setu.identity.application.UserRegistrationService#registerIfNew}'s javadoc for why a sealed result's
     * nested records can't cross a module boundary even through a {@code @NamedInterface} class. Empty covers
     * every non-success {@link AdmitResult} the same way — a genuine, non-seed caller reports its own generic
     * "admission failed" outcome rather than needing to distinguish which of the three ways it failed.
     */
    @Transactional
    public Optional<UUID> admitStudentIfPossible(UUID tenantId, UUID personId, String admissionNo, String rollNo,
                                                  java.time.LocalDate admissionDate) {
        return switch (admitStudent(tenantId, personId, admissionNo, rollNo, admissionDate)) {
            case AdmitResult.Success s -> Optional.of(s.studentId());
            default -> Optional.empty();
        };
    }

}
