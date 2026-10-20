package com.setu.enrollment.application;

import com.setu.catalog.application.CurriculumService;
import com.setu.catalog.application.ProgramService;
import com.setu.directory.application.StudentProfileService;
import com.setu.enrollment.domain.StudentProgram;
import com.setu.enrollment.domain.StudentProgramStatus;
import com.setu.enrollment.infrastructure.StudentProgramInsertGuard;
import com.setu.enrollment.infrastructure.StudentProgramRepository;
import com.setu.institution.application.CollegeService;
import com.setu.shared.authorization.AuthorizationContext;
import com.setu.shared.authorization.ResourceRef;
import com.setu.shared.governance.AuditLogWriter;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Student-program history (Day 6 §6). The ONLY entity built in the
 * {@code enrollment} module for Day 6 — this is intentionally NOT course
 * enrollment/timetable (Day 6 explicitly excludes both).
 * <p>
 * Composes four other modules' narrow read ports — directory (student
 * tenant), catalog (program tenant, curriculum/program compatibility),
 * institution (the {@code allow_dual_program} tenant policy) — each
 * exposing only a bare id/boolean, never an entity. This is the most
 * cross-module-dependent service in the codebase so far; every dependency
 * is listed and justified on the port class it calls, per the Day 6 §13
 * ceremony.
 */
@org.springframework.modulith.NamedInterface // Day 15 §demo-data: com.setu.shared.seed.DemoDataSeeder needs this cross-module
@Service
public class StudentProgramService {

    private static final String MANAGE_PERMISSION = "MANAGE_STUDENT_PROGRAMS";

    private final StudentProgramRepository studentProgramRepository;
    private final StudentProgramInsertGuard studentProgramInsertGuard;
    private final StudentProfileService studentProfileService;
    private final ProgramService programService;
    private final CurriculumService curriculumService;
    private final CollegeService collegeService;
    private final AuthorizationContext authorizationContext;
    private final AuditLogWriter auditLogWriter;

    public StudentProgramService(StudentProgramRepository studentProgramRepository,
                                  StudentProgramInsertGuard studentProgramInsertGuard,
                                  StudentProfileService studentProfileService, ProgramService programService,
                                  CurriculumService curriculumService, CollegeService collegeService,
                                  AuthorizationContext authorizationContext, AuditLogWriter auditLogWriter) {
        this.studentProgramRepository = studentProgramRepository;
        this.studentProgramInsertGuard = studentProgramInsertGuard;
        this.studentProfileService = studentProfileService;
        this.programService = programService;
        this.curriculumService = curriculumService;
        this.collegeService = collegeService;
        this.authorizationContext = authorizationContext;
        this.auditLogWriter = auditLogWriter;
    }

    public sealed interface AssignResult {
        record Success(UUID studentProgramId) implements AssignResult {
        }

        record StudentNotFound() implements AssignResult {
        }

        record ProgramNotFound() implements AssignResult {
        }

        record TenantMismatch() implements AssignResult {
        }

        record CurriculumProgramMismatch() implements AssignResult {
        }

        record InvalidDates(String message) implements AssignResult {
        }

        record DuplicateActivePrimaryProgram() implements AssignResult {
        }

        /** Day 6 §6: "do not hard-code 'only one program ever'" — but a SECOND active program (non-primary) still requires the tenant's allow_dual_program policy, per docs/erd/02-academic.md. */
        record SecondaryProgramNotAllowedByPolicy() implements AssignResult {
        }
    }

    @Transactional
    public AssignResult assign(UUID studentId, UUID programId, UUID curriculumId, int admissionYear,
                                boolean primary, LocalDate startsAt) {
        Optional<UUID> studentTenant = studentProfileService.findTenantId(studentId);
        if (studentTenant.isEmpty()) {
            return new AssignResult.StudentNotFound();
        }
        Optional<UUID> programTenant = programService.findTenantId(programId);
        if (programTenant.isEmpty()) {
            return new AssignResult.ProgramNotFound();
        }
        if (!studentTenant.get().equals(programTenant.get())) {
            return new AssignResult.TenantMismatch();
        }
        UUID tenantId = studentTenant.get();
        authorizationContext.assertCan(MANAGE_PERMISSION, ResourceRef.of("student_program", UUID.randomUUID(), tenantId));

        if (!curriculumService.belongsToProgram(curriculumId, programId)) {
            return new AssignResult.CurriculumProgramMismatch();
        }

        if (!primary) {
            boolean hasAnyActive = studentProgramRepository.findByStudentId(studentId).stream()
                .anyMatch(sp -> sp.status() == StudentProgramStatus.ACTIVE);
            if (hasAnyActive && !collegeService.isDualProgramAllowed(tenantId)) {
                return new AssignResult.SecondaryProgramNotAllowedByPolicy();
            }
        }

        UUID actor = authorizationContext.currentUserId();
        StudentProgram studentProgram;
        try {
            studentProgram = new StudentProgram(UUID.randomUUID(), tenantId, studentId, programId, curriculumId,
                admissionYear, primary, startsAt, null);
        } catch (IllegalArgumentException e) {
            return new AssignResult.InvalidDates(e.getMessage());
        }
        try {
            studentProgramInsertGuard.insert(studentProgram);
        } catch (DataIntegrityViolationException e) {
            return new AssignResult.DuplicateActivePrimaryProgram();
        }
        auditLogWriter.write(tenantId, actor, "STUDENT_PROGRAM_ASSIGNED", "enrollment.student_programs",
            studentProgram.id(), Map.of("studentId", studentId, "programId", programId, "isPrimary", primary));
        return new AssignResult.Success(studentProgram.id());
    }

    @Transactional(readOnly = true)
    public List<StudentProgram> history(UUID studentId) {
        Optional<UUID> tenantId = studentProfileService.findTenantId(studentId);
        if (tenantId.isEmpty()) {
            return List.of();
        }
        authorizationContext.assertCan(MANAGE_PERMISSION, ResourceRef.of("student_program", studentId, tenantId.get()));
        return studentProgramRepository.findByStudentId(studentId);
    }

    /**
     * The curriculum of this student's ACTIVE program, with NO authorization check of its own — a narrow cross-module
     * read port for a caller that has already authorized access to the student's academic record ({@code
     * record.AcademicRecordService}: self / admin / guardian grant). {@link #history} cannot serve that caller because
     * it demands the registrar-only {@code MANAGE_STUDENT_PROGRAMS}, which would make a student's own degree progress
     * unreadable to the student.
     */
    @Transactional(readOnly = true)
    public Optional<UUID> activeCurriculumIdUnchecked(UUID studentId) {
        return studentProgramRepository.findByStudentId(studentId).stream()
            .filter(p -> p.status() == StudentProgramStatus.ACTIVE)
            .map(StudentProgram::curriculumId)
            .findFirst();
    }

    /** The ACTIVE program of this student, with no authorization check of its own: same precedent and same callers' obligations as {@link #activeCurriculumIdUnchecked}. */
    @Transactional(readOnly = true)
    public Optional<UUID> activeProgramIdUnchecked(UUID studentId) {
        return studentProgramRepository.findByStudentId(studentId).stream()
            .filter(p -> p.status() == StudentProgramStatus.ACTIVE)
            .map(StudentProgram::programId)
            .findFirst();
    }

    /**
     * The year this student is expected to graduate: the admission year of their ACTIVE program plus the program's length in years (its terms divided by the college's
     * terms per year, rounded up). Empty if they have no active program or the program has no length. No authorization of its own: same precedent and callers' obligations
     * as {@link #activeProgramIdUnchecked}.
     */
    @Transactional(readOnly = true)
    public Optional<Integer> expectedGraduationYearUnchecked(UUID studentId) {
        return studentProgramRepository.findByStudentId(studentId).stream()
            .filter(p -> p.status() == StudentProgramStatus.ACTIVE)
            .findFirst()
            .flatMap(p -> programService.durationTermsUnchecked(p.programId()).map(terms -> {
                int perYear = collegeService.termsPerYear(p.tenantId());
                return p.admissionYear() + (terms + perYear - 1) / perYear;
            }));
    }

    public enum StatusChangeResult {SUCCESS, NOT_FOUND, INVALID_TRANSITION}

    @Transactional
    public StatusChangeResult changeStatus(UUID studentProgramId, StudentProgramStatus target) {
        Optional<StudentProgram> maybeSp = studentProgramRepository.findById(studentProgramId);
        if (maybeSp.isEmpty()) {
            return StatusChangeResult.NOT_FOUND;
        }
        StudentProgram studentProgram = maybeSp.get();
        authorizationContext.assertCan(MANAGE_PERMISSION,
            ResourceRef.of("student_program", studentProgram.id(), studentProgram.tenantId()));
        if (!studentProgram.canTransitionTo(target)) {
            return StatusChangeResult.INVALID_TRANSITION;
        }
        var from = studentProgram.status();
        studentProgram.transitionTo(target, LocalDate.now());
        studentProgramRepository.save(studentProgram);
        UUID actor = authorizationContext.currentUserId();
        auditLogWriter.write(studentProgram.tenantId(), actor, "STUDENT_PROGRAM_CHANGED",
            "enrollment.student_programs", studentProgram.id(), Map.of("from", from.name(), "to", target.name()));
        return StatusChangeResult.SUCCESS;
    }
}
