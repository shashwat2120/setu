package com.setu.directory.application;

import com.setu.directory.domain.Guardian;
import com.setu.directory.domain.StudentGuardian;
import com.setu.directory.domain.StudentProfile;
import com.setu.directory.infrastructure.GuardianRepository;
import com.setu.directory.infrastructure.StudentGuardianInsertGuard;
import com.setu.directory.infrastructure.StudentGuardianRepository;
import com.setu.directory.infrastructure.StudentProfileRepository;
import com.setu.shared.authorization.AuthorizationContext;
import com.setu.shared.authorization.ResourceRef;
import com.setu.shared.governance.AuditLogWriter;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Student <-> guardian relationships (Day 6 §7) — the relationship record only, never an access grant by itself (see guardianaccess.GuardianAccessService for that, kept completely separate). */
@org.springframework.modulith.NamedInterface // Day 15 §demo-data: com.setu.shared.seed.DemoDataSeeder needs this cross-module
@Service
public class StudentGuardianService {

    private static final String MANAGE_PERMISSION = "MANAGE_GUARDIANS";

    private final StudentGuardianRepository studentGuardianRepository;
    private final StudentGuardianInsertGuard studentGuardianInsertGuard;
    private final StudentProfileRepository studentProfileRepository;
    private final GuardianRepository guardianRepository;
    private final AuthorizationContext authorizationContext;
    private final AuditLogWriter auditLogWriter;

    public StudentGuardianService(StudentGuardianRepository studentGuardianRepository,
                                   StudentGuardianInsertGuard studentGuardianInsertGuard,
                                   StudentProfileRepository studentProfileRepository,
                                   GuardianRepository guardianRepository, AuthorizationContext authorizationContext,
                                   AuditLogWriter auditLogWriter) {
        this.studentGuardianRepository = studentGuardianRepository;
        this.studentGuardianInsertGuard = studentGuardianInsertGuard;
        this.studentProfileRepository = studentProfileRepository;
        this.guardianRepository = guardianRepository;
        this.authorizationContext = authorizationContext;
        this.auditLogWriter = auditLogWriter;
    }

    public sealed interface LinkResult {
        record Success(UUID studentGuardianId) implements LinkResult {
        }

        record StudentNotFound() implements LinkResult {
        }

        record GuardianNotFound() implements LinkResult {
        }

        /** Day 6 §7: "validate student and guardian belong to the same tenant." */
        record TenantMismatch() implements LinkResult {
        }

        record DuplicateRelationship() implements LinkResult {
        }
    }

    @Transactional
    public LinkResult link(UUID studentId, UUID guardianId, String relationshipType, boolean primary,
                            boolean emergencyContact) {
        Optional<StudentProfile> student = studentProfileRepository.findById(studentId);
        if (student.isEmpty()) {
            return new LinkResult.StudentNotFound();
        }
        Optional<Guardian> guardian = guardianRepository.findById(guardianId);
        if (guardian.isEmpty()) {
            return new LinkResult.GuardianNotFound();
        }
        if (!student.get().tenantId().equals(guardian.get().tenantId())) {
            return new LinkResult.TenantMismatch();
        }
        UUID tenantId = student.get().tenantId();
        authorizationContext.assertCan(MANAGE_PERMISSION, ResourceRef.of("student_guardian", UUID.randomUUID(), tenantId));

        UUID actor = authorizationContext.currentUserId();
        Instant now = Instant.now();

        if (primary) {
            // Primary-guardian semantics are application-enforced (no DB
            // constraint exists for "at most one primary per student") —
            // unset any existing primary link for this student first.
            studentGuardianRepository.findByStudentId(studentId).stream()
                .filter(StudentGuardian::isPrimary)
                .forEach(existing -> {
                    existing.clearPrimary(actor, now);
                    studentGuardianRepository.save(existing);
                });
        }

        StudentGuardian link = new StudentGuardian(UUID.randomUUID(), tenantId, studentId, guardianId,
            relationshipType, primary, emergencyContact, actor, now);
        try {
            studentGuardianInsertGuard.insert(link);
        } catch (DataIntegrityViolationException e) {
            return new LinkResult.DuplicateRelationship();
        }
        auditLogWriter.write(tenantId, actor, "STUDENT_GUARDIAN_LINKED", "directory.student_guardians", link.id(),
            Map.of("studentId", studentId, "guardianId", guardianId, "relationshipType", relationshipType));
        return new LinkResult.Success(link.id());
    }

    /** Flat cross-module result for administrative onboarding; detailed failure reasons remain directory-owned. */
    @Transactional
    public Optional<UUID> linkIfPossible(UUID studentId, UUID guardianId, String relationshipType, boolean primary,
                                         boolean emergencyContact) {
        LinkResult result = link(studentId, guardianId, relationshipType, primary, emergencyContact);
        return result instanceof LinkResult.Success success ? Optional.of(success.studentGuardianId()) : Optional.empty();
    }

    public enum UnlinkResult {SUCCESS, NOT_FOUND}

    @Transactional
    public UnlinkResult unlink(UUID studentGuardianId) {
        Optional<StudentGuardian> maybeLink = studentGuardianRepository.findById(studentGuardianId);
        if (maybeLink.isEmpty()) {
            return UnlinkResult.NOT_FOUND;
        }
        StudentGuardian link = maybeLink.get();
        authorizationContext.assertCan(MANAGE_PERMISSION, ResourceRef.of("student_guardian", link.id(), link.tenantId()));
        UUID actor = authorizationContext.currentUserId();
        auditLogWriter.write(link.tenantId(), actor, "STUDENT_GUARDIAN_UNLINKED", "directory.student_guardians",
            link.id(), Map.of("studentId", link.studentId(), "guardianId", link.guardianId()));
        studentGuardianRepository.delete(link);
        return UnlinkResult.SUCCESS;
    }

    @Transactional(readOnly = true)
    public List<StudentGuardian> guardiansForStudent(UUID studentId) {
        Optional<StudentProfile> student = studentProfileRepository.findById(studentId);
        if (student.isEmpty()) {
            return List.of();
        }
        authorizationContext.assertCan(MANAGE_PERMISSION, ResourceRef.of("student_guardian", studentId, student.get().tenantId()));
        return studentGuardianRepository.findByStudentId(studentId);
    }

    @Transactional(readOnly = true)
    public List<StudentGuardian> studentsForGuardian(UUID guardianId) {
        Optional<Guardian> guardian = guardianRepository.findById(guardianId);
        if (guardian.isEmpty()) {
            return List.of();
        }
        authorizationContext.assertCan(MANAGE_PERMISSION, ResourceRef.of("student_guardian", guardianId, guardian.get().tenantId()));
        return studentGuardianRepository.findByGuardianId(guardianId);
    }
}
