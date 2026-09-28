package com.setu.catalog.application;

import com.setu.catalog.domain.Program;
import com.setu.catalog.domain.ProgramStatus;
import com.setu.catalog.infrastructure.ProgramInsertGuard;
import com.setu.catalog.infrastructure.ProgramRepository;
import com.setu.institution.application.DepartmentService;
import com.setu.shared.authorization.AuthorizationContext;
import com.setu.shared.authorization.ResourceRef;
import com.setu.shared.governance.AuditLogWriter;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Program CRUD (Day 6 §5). A program is academic/catalog identity — NOT a
 * cohort/class and NOT a course section. Department tenant ownership is
 * validated through {@code institution.DepartmentService.findTenantId}
 * (see that class's javadoc for the full cross-module justification) —
 * never trusting a client-supplied department's tenant.
 * <p>
 * {@code @NamedInterface} on this class (Day 6 §13 ceremony): caller is
 * {@code enrollment.application.StudentProgramService}, which must validate
 * a student-program assignment's program actually exists and belongs to
 * the same tenant before accepting it. Only {@link #findTenantId} is the
 * exposed contract — a bare tenant id, not the {@code Program} entity, and
 * with no authorization check of its own for the same reason
 * {@code DepartmentService.findTenantId} has none (see that class).
 * Verified via {@code ModularityTests}.
 */
@org.springframework.modulith.NamedInterface
@Service
public class ProgramService {

    private static final String MANAGE_PERMISSION = "MANAGE_PROGRAMS";
    private static final String VIEW_PERMISSION = "VIEW_TENANT_DATA";

    private final ProgramRepository programRepository;
    private final ProgramInsertGuard programInsertGuard;
    private final DepartmentService departmentService;
    private final AuthorizationContext authorizationContext;
    private final AuditLogWriter auditLogWriter;

    public ProgramService(ProgramRepository programRepository, ProgramInsertGuard programInsertGuard,
                           DepartmentService departmentService,
                           AuthorizationContext authorizationContext, AuditLogWriter auditLogWriter) {
        this.programRepository = programRepository;
        this.programInsertGuard = programInsertGuard;
        this.departmentService = departmentService;
        this.authorizationContext = authorizationContext;
        this.auditLogWriter = auditLogWriter;
    }

    public sealed interface CreateResult {
        record Success(UUID programId) implements CreateResult {
        }

        record DepartmentNotFound() implements CreateResult {
        }

        record CodeAlreadyInUse() implements CreateResult {
        }

        record InvalidProgramData(String message) implements CreateResult {
        }
    }

    @Transactional
    public CreateResult create(UUID departmentId, String code, String name, String degreeType,
                                Integer durationTerms, BigDecimal creditsRequired) {
        Optional<UUID> tenantId = departmentService.findTenantId(departmentId);
        if (tenantId.isEmpty()) {
            return new CreateResult.DepartmentNotFound();
        }
        authorizationContext.assertCan(MANAGE_PERMISSION, ResourceRef.of("program", UUID.randomUUID(), tenantId.get()));

        UUID actor = authorizationContext.currentUserId();
        Program program;
        try {
            program = new Program(UUID.randomUUID(), tenantId.get(), departmentId, code, name, degreeType,
                durationTerms, creditsRequired, actor, Instant.now());
        } catch (IllegalArgumentException e) {
            return new CreateResult.InvalidProgramData(e.getMessage());
        }
        try {
            programInsertGuard.insert(program);
        } catch (DataIntegrityViolationException e) {
            return new CreateResult.CodeAlreadyInUse();
        }
        auditLogWriter.write(tenantId.get(), actor, "PROGRAM_CREATED", "catalog.programs", program.id(),
            Map.of("code", code, "name", name, "departmentId", departmentId));
        return new CreateResult.Success(program.id());
    }

    @Transactional(readOnly = true)
    public Optional<Program> get(UUID programId) {
        Optional<Program> program = programRepository.findById(programId);
        program.ifPresent(p -> authorizationContext.assertCan(VIEW_PERMISSION,
            ResourceRef.of("program", p.id(), p.tenantId())));
        return program;
    }

    @Transactional(readOnly = true)
    public List<Program> listForTenant(UUID tenantId) {
        authorizationContext.assertCan(VIEW_PERMISSION, ResourceRef.of("program", UUID.randomUUID(), tenantId));
        return programRepository.findByTenantId(tenantId);
    }

    public enum UpdateResult {SUCCESS, NOT_FOUND, INVALID_DATA}

    @Transactional
    public UpdateResult update(UUID programId, String name, String degreeType, Integer durationTerms,
                                BigDecimal creditsRequired) {
        Optional<Program> maybeProgram = programRepository.findById(programId);
        if (maybeProgram.isEmpty()) {
            return UpdateResult.NOT_FOUND;
        }
        Program program = maybeProgram.get();
        authorizationContext.assertCan(MANAGE_PERMISSION, ResourceRef.of("program", program.id(), program.tenantId()));
        UUID actor = authorizationContext.currentUserId();
        try {
            program.update(name, degreeType, durationTerms, creditsRequired, actor, Instant.now());
        } catch (IllegalArgumentException e) {
            return UpdateResult.INVALID_DATA;
        }
        programRepository.save(program);
        auditLogWriter.write(program.tenantId(), actor, "PROGRAM_UPDATED", "catalog.programs", program.id(),
            Map.of("name", name));
        return UpdateResult.SUCCESS;
    }

    public enum StatusChangeResult {SUCCESS, NOT_FOUND, INVALID_TRANSITION}

    @Transactional
    public StatusChangeResult changeStatus(UUID programId, ProgramStatus target) {
        Optional<Program> maybeProgram = programRepository.findById(programId);
        if (maybeProgram.isEmpty()) {
            return StatusChangeResult.NOT_FOUND;
        }
        Program program = maybeProgram.get();
        authorizationContext.assertCan(MANAGE_PERMISSION, ResourceRef.of("program", program.id(), program.tenantId()));
        if (!program.canTransitionTo(target)) {
            return StatusChangeResult.INVALID_TRANSITION;
        }
        UUID actor = authorizationContext.currentUserId();
        var from = program.status();
        program.transitionTo(target, actor, Instant.now());
        programRepository.save(program);
        auditLogWriter.write(program.tenantId(), actor, "PROGRAM_STATUS_CHANGED", "catalog.programs", program.id(),
            Map.of("from", from.name(), "to", target.name()));
        return StatusChangeResult.SUCCESS;
    }

    /** Used cross-module by enrollment.StudentProgramService to validate program/tenant consistency without exposing the Program entity itself. */
    @Transactional(readOnly = true)
    public Optional<UUID> findTenantId(UUID programId) {
        return programRepository.findById(programId).map(Program::tenantId);
    }

    /** Day 15 §demo-data: plain-UUID unwrap of {@link #create}. */
    @Transactional
    public UUID createForSeed(UUID departmentId, String code, String name, String degreeType,
                               Integer durationTerms, java.math.BigDecimal creditsRequired) {
        return switch (create(departmentId, code, name, degreeType, durationTerms, creditsRequired)) {
            case CreateResult.Success s -> s.programId();
            default -> throw new IllegalStateException("seed program creation failed: " + code);
        };
    }

}
