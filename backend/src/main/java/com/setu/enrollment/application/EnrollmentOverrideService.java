package com.setu.enrollment.application;

import com.setu.directory.application.StudentProfileService;
import com.setu.enrollment.domain.CourseSection;
import com.setu.enrollment.domain.EnrollmentOverride;
import com.setu.enrollment.domain.OverrideType;
import com.setu.enrollment.infrastructure.CourseSectionRepository;
import com.setu.enrollment.infrastructure.EnrollmentOverrideRepository;
import com.setu.shared.authorization.AuthorizationContext;
import com.setu.shared.authorization.ResourceRef;
import com.setu.shared.governance.AuditLogWriter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Enrollment overrides (Day 8 §4) — always an explicit, approved exception,
 * never a silent bypass (canonical spec §3). Per the authorization matrix's
 * Enrollment-override row, only COLLEGE_ADMIN may approve, faculty may only
 * propose pending admin approval, and a student may only request. Since
 * {@code enrollment_overrides.approved_by} is a mandatory column with no
 * separate proposal/pending-status table anywhere in the ERD (creating a
 * row already means approved), only an actor holding
 * {@code MANAGE_ENROLLMENT_OVERRIDES} (COLLEGE_ADMIN/SUPER_ADMIN) can
 * create one — a faculty/student propose workflow would require inventing
 * a new table this day's scope doesn't call for, so it is not built;
 * disclosed in the Day 8 report.
 */
@Service
public class EnrollmentOverrideService {

    private static final String MANAGE_PERMISSION = "MANAGE_ENROLLMENT_OVERRIDES";

    private final EnrollmentOverrideRepository overrideRepository;
    private final CourseSectionRepository courseSectionRepository;
    private final StudentProfileService studentProfileService;
    private final AuthorizationContext authorizationContext;
    private final AuditLogWriter auditLogWriter;

    public EnrollmentOverrideService(EnrollmentOverrideRepository overrideRepository,
                                      CourseSectionRepository courseSectionRepository,
                                      StudentProfileService studentProfileService,
                                      AuthorizationContext authorizationContext, AuditLogWriter auditLogWriter) {
        this.overrideRepository = overrideRepository;
        this.courseSectionRepository = courseSectionRepository;
        this.studentProfileService = studentProfileService;
        this.authorizationContext = authorizationContext;
        this.auditLogWriter = auditLogWriter;
    }

    public sealed interface CreateResult {
        record Success(UUID overrideId) implements CreateResult {
        }

        record StudentNotFound() implements CreateResult {
        }

        record SectionNotFound() implements CreateResult {
        }

        record TenantMismatch() implements CreateResult {
        }

        record SelfApprovalRejected() implements CreateResult {
        }

        record InvalidData(String message) implements CreateResult {
        }
    }

    @Transactional
    public CreateResult create(UUID studentId, UUID sectionId, OverrideType type, String reason, Instant expiresAt) {
        Optional<StudentProfileService.StudentTenantOwner> studentInfo = studentProfileService.findTenantAndOwnerUserId(studentId);
        if (studentInfo.isEmpty()) {
            return new CreateResult.StudentNotFound();
        }
        UUID tenantId = studentInfo.get().tenantId();
        authorizationContext.assertCan(MANAGE_PERMISSION, ResourceRef.of("enrollment_override", UUID.randomUUID(), tenantId));

        Optional<CourseSection> section = courseSectionRepository.findById(sectionId);
        if (section.isEmpty()) {
            return new CreateResult.SectionNotFound();
        }
        if (!section.get().tenantId().equals(tenantId)) {
            return new CreateResult.TenantMismatch();
        }

        UUID actor = authorizationContext.currentUserId();
        // Day 8 §4: "self-approval must be rejected where policy requires separation" — an approver can never be the beneficiary student themselves.
        if (actor.equals(studentInfo.get().ownerUserId())) {
            return new CreateResult.SelfApprovalRejected();
        }

        EnrollmentOverride override;
        try {
            override = new EnrollmentOverride(UUID.randomUUID(), tenantId, studentId, sectionId, type, reason, actor, expiresAt);
        } catch (IllegalArgumentException e) {
            return new CreateResult.InvalidData(e.getMessage());
        }
        overrideRepository.save(override);
        auditLogWriter.write(tenantId, actor, "ENROLLMENT_OVERRIDE_CREATED", "enrollment.enrollment_overrides",
            override.id(), Map.of("studentId", studentId, "sectionId", sectionId, "type", type.name()));
        return new CreateResult.Success(override.id());
    }

    public enum RevokeResult {SUCCESS, NOT_FOUND, INVALID_TRANSITION}

    @Transactional
    public RevokeResult revoke(UUID overrideId) {
        Optional<EnrollmentOverride> maybe = overrideRepository.findById(overrideId);
        if (maybe.isEmpty()) {
            return RevokeResult.NOT_FOUND;
        }
        EnrollmentOverride override = maybe.get();
        authorizationContext.assertCan(MANAGE_PERMISSION, ResourceRef.of("enrollment_override", override.id(), override.tenantId()));
        try {
            override.revoke();
        } catch (IllegalStateException e) {
            return RevokeResult.INVALID_TRANSITION;
        }
        overrideRepository.save(override);
        UUID actor = authorizationContext.currentUserId();
        auditLogWriter.write(override.tenantId(), actor, "ENROLLMENT_OVERRIDE_REVOKED", "enrollment.enrollment_overrides",
            override.id(), Map.of());
        return RevokeResult.SUCCESS;
    }

    @Transactional(readOnly = true)
    public List<EnrollmentOverride> listForStudent(UUID studentId) {
        Optional<StudentProfileService.StudentTenantOwner> studentInfo = studentProfileService.findTenantAndOwnerUserId(studentId);
        if (studentInfo.isEmpty()) {
            return List.of();
        }
        boolean isSelf = authorizationContext.currentUserId().equals(studentInfo.get().ownerUserId());
        if (!isSelf) {
            authorizationContext.assertCan(MANAGE_PERMISSION, ResourceRef.of("enrollment_override", UUID.randomUUID(), studentInfo.get().tenantId()));
        }
        return overrideRepository.findByStudentId(studentId);
    }
}
