package com.setu.catalog.application;

import com.setu.catalog.domain.Curriculum;
import com.setu.catalog.domain.Program;
import com.setu.catalog.infrastructure.CurriculumRepository;
import com.setu.catalog.infrastructure.ProgramRepository;
import com.setu.shared.authorization.AuthorizationContext;
import com.setu.shared.authorization.ResourceRef;
import com.setu.shared.governance.AuditLogWriter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Curricula (Day 6 §5 minimal reference, expanded Day 7 §4 to full
 * versioning). A curriculum is one dated VERSION of a program's
 * requirements; multiple versions may coexist for the same program
 * (docs/state-machines.md has no entry for Curriculum — there is no status
 * lifecycle here at all, only date-ranged versions, so none is invented).
 * Historical versions are never destructively rewritten — see
 * {@link Curriculum#update} for the exact narrow mutation allowed.
 * <p>
 * {@code @NamedInterface} on this class (Day 6 §13 ceremony, unchanged by
 * Day 7): caller is {@code enrollment.application.StudentProgramService};
 * only {@link #belongsToProgram} is exposed. Verified via
 * {@code ModularityTests}.
 */
@org.springframework.modulith.NamedInterface
@Service
public class CurriculumService {

    private static final String MANAGE_PERMISSION = "MANAGE_CURRICULUM";
    private static final String VIEW_PERMISSION = "VIEW_TENANT_DATA";

    private final CurriculumRepository curriculumRepository;
    private final ProgramRepository programRepository;
    private final AuthorizationContext authorizationContext;
    private final AuditLogWriter auditLogWriter;

    public CurriculumService(CurriculumRepository curriculumRepository, ProgramRepository programRepository,
                              AuthorizationContext authorizationContext, AuditLogWriter auditLogWriter) {
        this.curriculumRepository = curriculumRepository;
        this.programRepository = programRepository;
        this.authorizationContext = authorizationContext;
        this.auditLogWriter = auditLogWriter;
    }

    public sealed interface CreateResult {
        record Success(UUID curriculumId) implements CreateResult {
        }

        record ProgramNotFound() implements CreateResult {
        }

        record InvalidDateRange() implements CreateResult {
        }

        /** Day 7 §4: "prevent invalid overlapping... effective-version semantics" — two curricula for the same program may never claim overlapping date ranges. */
        record OverlapsExistingVersion() implements CreateResult {
        }
    }

    @Transactional
    public CreateResult createReference(UUID programId, String name, String versionLabel, LocalDate effectiveFrom,
                                         LocalDate effectiveTo) {
        Optional<Program> program = programRepository.findById(programId);
        if (program.isEmpty()) {
            return new CreateResult.ProgramNotFound();
        }
        UUID tenantId = program.get().tenantId();
        authorizationContext.assertCan(MANAGE_PERMISSION, ResourceRef.of("curriculum", UUID.randomUUID(), tenantId));

        boolean overlaps = curriculumRepository.findByProgramId(programId).stream()
            .anyMatch(existing -> existing.overlapsWith(effectiveFrom, effectiveTo));
        if (overlaps) {
            return new CreateResult.OverlapsExistingVersion();
        }

        UUID actor = authorizationContext.currentUserId();
        Curriculum curriculum;
        try {
            curriculum = new Curriculum(UUID.randomUUID(), tenantId, programId, name, versionLabel, effectiveFrom,
                effectiveTo, actor, Instant.now());
        } catch (IllegalArgumentException e) {
            return new CreateResult.InvalidDateRange();
        }
        curriculumRepository.save(curriculum);
        auditLogWriter.write(tenantId, actor, "CURRICULUM_CREATED", "catalog.curricula", curriculum.id(),
            Map.of("programId", programId, "versionLabel", versionLabel));
        return new CreateResult.Success(curriculum.id());
    }

    @Transactional(readOnly = true)
    public Optional<Curriculum> get(UUID curriculumId) {
        Optional<Curriculum> curriculum = curriculumRepository.findById(curriculumId);
        curriculum.ifPresent(c -> authorizationContext.assertCan(VIEW_PERMISSION,
            ResourceRef.of("curriculum", c.id(), c.tenantId())));
        return curriculum;
    }

    @Transactional(readOnly = true)
    public List<Curriculum> listByProgram(UUID programId, UUID tenantId) {
        authorizationContext.assertCan(VIEW_PERMISSION, ResourceRef.of("curriculum", UUID.randomUUID(), tenantId));
        return curriculumRepository.findByProgramId(programId);
    }

    public sealed interface UpdateResult {
        record Success() implements UpdateResult {
        }

        record NotFound() implements UpdateResult {
        }

        record InvalidDateRange() implements UpdateResult {
        }

        record OverlapsExistingVersion() implements UpdateResult {
        }
    }

    @Transactional
    public UpdateResult update(UUID curriculumId, String name, LocalDate newEffectiveTo) {
        Optional<Curriculum> maybeCurriculum = curriculumRepository.findById(curriculumId);
        if (maybeCurriculum.isEmpty()) {
            return new UpdateResult.NotFound();
        }
        Curriculum curriculum = maybeCurriculum.get();
        authorizationContext.assertCan(MANAGE_PERMISSION,
            ResourceRef.of("curriculum", curriculum.id(), curriculum.tenantId()));

        if (newEffectiveTo != null) {
            boolean overlaps = curriculumRepository.findByProgramId(curriculum.programId()).stream()
                .filter(other -> !other.id().equals(curriculumId))
                .anyMatch(other -> other.overlapsWith(curriculum.effectiveFrom(), newEffectiveTo));
            if (overlaps) {
                return new UpdateResult.OverlapsExistingVersion();
            }
        }

        LocalDate previousEffectiveTo = curriculum.effectiveTo();
        UUID actor = authorizationContext.currentUserId();
        try {
            curriculum.update(name, newEffectiveTo, actor, Instant.now());
        } catch (IllegalArgumentException e) {
            return new UpdateResult.InvalidDateRange();
        }
        curriculumRepository.save(curriculum);

        if (!Objects.equals(previousEffectiveTo, newEffectiveTo)) {
            auditLogWriter.write(curriculum.tenantId(), actor, "CURRICULUM_VERSION_CHANGED", "catalog.curricula",
                curriculum.id(), Map.of("previousEffectiveTo", String.valueOf(previousEffectiveTo),
                    "newEffectiveTo", String.valueOf(newEffectiveTo)));
        } else {
            auditLogWriter.write(curriculum.tenantId(), actor, "CURRICULUM_UPDATED", "catalog.curricula",
                curriculum.id(), Map.of("name", name));
        }
        return new UpdateResult.Success();
    }

    /** See this class's javadoc for the full Day 6 §13 cross-module justification. */
    public boolean belongsToProgram(UUID curriculumId, UUID programId) {
        return curriculumRepository.findById(curriculumId)
            .map(c -> c.programId().equals(programId))
            .orElse(false);
    }

    /** Day 15 §demo-data: plain-UUID unwrap of {@link #createReference}. */
    @Transactional
    public UUID createReferenceForSeed(UUID programId, String name, String versionLabel,
                                        java.time.LocalDate effectiveFrom, java.time.LocalDate effectiveTo) {
        return switch (createReference(programId, name, versionLabel, effectiveFrom, effectiveTo)) {
            case CreateResult.Success s -> s.curriculumId();
            default -> throw new IllegalStateException("seed curriculum creation failed: " + name);
        };
    }

}
