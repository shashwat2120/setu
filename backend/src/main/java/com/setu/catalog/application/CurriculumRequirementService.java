package com.setu.catalog.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.setu.catalog.domain.Course;
import com.setu.catalog.domain.Curriculum;
import com.setu.catalog.domain.CurriculumRequirement;
import com.setu.catalog.domain.CurriculumRequirementCourse;
import com.setu.catalog.domain.RequirementRole;
import com.setu.catalog.infrastructure.CourseRepository;
import com.setu.catalog.infrastructure.CurriculumRepository;
import com.setu.catalog.infrastructure.CurriculumRequirementCourseInsertGuard;
import com.setu.catalog.infrastructure.CurriculumRequirementCourseRepository;
import com.setu.catalog.infrastructure.CurriculumRequirementRepository;
import com.setu.shared.authorization.AuthorizationContext;
import com.setu.shared.authorization.ResourceRef;
import com.setu.shared.governance.AuditLogWriter;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Curriculum requirements and their required-course mappings (Day 7 §5) —
 * the actual degree-audit rules a {@link Curriculum} version specifies,
 * intentionally deferred past Day 6. Degree-progress calculation (evaluating
 * a specific student against these rules) is explicitly out of scope here;
 * this module only stores and validates the rules themselves.
 * <p>
 * {@code rule_json} has no canonical structure documented anywhere in this
 * project (no ERD/spec entry defines one) — rather than inventing an
 * elaborate schema, the smallest defensible validation is applied: it must
 * be syntactically valid JSON, and if present must be a JSON object (not a
 * scalar/array), matching the column's own {@code '{}'}-shaped default.
 * This gap and decision are disclosed in the Day 7 report.
 */
@Service
public class CurriculumRequirementService {

    private static final String MANAGE_PERMISSION = "MANAGE_CURRICULUM";
    private static final String VIEW_PERMISSION = "VIEW_TENANT_DATA";

    private final CurriculumRequirementRepository requirementRepository;
    private final CurriculumRequirementCourseRepository requirementCourseRepository;
    private final CurriculumRequirementCourseInsertGuard requirementCourseInsertGuard;
    private final CurriculumRepository curriculumRepository;
    private final CourseRepository courseRepository;
    private final AuthorizationContext authorizationContext;
    private final AuditLogWriter auditLogWriter;
    private final ObjectMapper objectMapper;

    public CurriculumRequirementService(CurriculumRequirementRepository requirementRepository,
                                         CurriculumRequirementCourseRepository requirementCourseRepository,
                                         CurriculumRequirementCourseInsertGuard requirementCourseInsertGuard,
                                         CurriculumRepository curriculumRepository, CourseRepository courseRepository,
                                         AuthorizationContext authorizationContext, AuditLogWriter auditLogWriter,
                                         ObjectMapper objectMapper) {
        this.requirementRepository = requirementRepository;
        this.requirementCourseRepository = requirementCourseRepository;
        this.requirementCourseInsertGuard = requirementCourseInsertGuard;
        this.curriculumRepository = curriculumRepository;
        this.courseRepository = courseRepository;
        this.authorizationContext = authorizationContext;
        this.auditLogWriter = auditLogWriter;
        this.objectMapper = objectMapper;
    }

    private boolean isValidRuleJson(String ruleJson) {
        if (ruleJson == null) {
            return true;
        }
        try {
            JsonNode node = objectMapper.readTree(ruleJson);
            return node.isObject();
        } catch (Exception e) {
            return false;
        }
    }

    public sealed interface CreateResult {
        record Success(UUID requirementId) implements CreateResult {
        }

        record CurriculumNotFound() implements CreateResult {
        }

        record InvalidData(String message) implements CreateResult {
        }

        record InvalidRuleJson() implements CreateResult {
        }
    }

    @Transactional
    public CreateResult create(UUID curriculumId, String groupCode, BigDecimal minCredits, Integer minCourses,
                                String ruleJson) {
        Optional<Curriculum> curriculum = curriculumRepository.findById(curriculumId);
        if (curriculum.isEmpty()) {
            return new CreateResult.CurriculumNotFound();
        }
        UUID tenantId = curriculum.get().tenantId();
        authorizationContext.assertCan(MANAGE_PERMISSION, ResourceRef.of("curriculum_requirement", UUID.randomUUID(), tenantId));

        if (!isValidRuleJson(ruleJson)) {
            return new CreateResult.InvalidRuleJson();
        }

        UUID actor = authorizationContext.currentUserId();
        CurriculumRequirement requirement;
        try {
            requirement = new CurriculumRequirement(UUID.randomUUID(), curriculumId, groupCode, minCredits,
                minCourses, ruleJson);
        } catch (IllegalArgumentException e) {
            return new CreateResult.InvalidData(e.getMessage());
        }
        requirementRepository.save(requirement);
        auditLogWriter.write(tenantId, actor, "CURRICULUM_REQUIREMENT_CREATED", "catalog.curriculum_requirements",
            requirement.id(), Map.of("curriculumId", curriculumId, "groupCode", groupCode));
        return new CreateResult.Success(requirement.id());
    }

    @Transactional(readOnly = true)
    public Optional<CurriculumRequirement> get(UUID requirementId) {
        Optional<CurriculumRequirement> requirement = requirementRepository.findById(requirementId);
        requirement.ifPresent(r -> {
            UUID tenantId = curriculumRepository.findById(r.curriculumId()).map(Curriculum::tenantId).orElseThrow();
            authorizationContext.assertCan(VIEW_PERMISSION, ResourceRef.of("curriculum_requirement", r.id(), tenantId));
        });
        return requirement;
    }

    @Transactional(readOnly = true)
    public List<CurriculumRequirement> listByCurriculum(UUID curriculumId) {
        UUID tenantId = curriculumRepository.findById(curriculumId).map(Curriculum::tenantId)
            .orElseThrow(() -> new IllegalArgumentException("curriculum not found"));
        authorizationContext.assertCan(VIEW_PERMISSION, ResourceRef.of("curriculum_requirement", UUID.randomUUID(), tenantId));
        return requirementRepository.findByCurriculumId(curriculumId);
    }

    public sealed interface UpdateResult {
        record Success() implements UpdateResult {
        }

        record NotFound() implements UpdateResult {
        }

        record InvalidData(String message) implements UpdateResult {
        }

        record InvalidRuleJson() implements UpdateResult {
        }
    }

    @Transactional
    public UpdateResult update(UUID requirementId, String groupCode, BigDecimal minCredits, Integer minCourses,
                                String ruleJson) {
        Optional<CurriculumRequirement> maybeRequirement = requirementRepository.findById(requirementId);
        if (maybeRequirement.isEmpty()) {
            return new UpdateResult.NotFound();
        }
        CurriculumRequirement requirement = maybeRequirement.get();
        UUID tenantId = curriculumRepository.findById(requirement.curriculumId()).map(Curriculum::tenantId).orElseThrow();
        authorizationContext.assertCan(MANAGE_PERMISSION, ResourceRef.of("curriculum_requirement", requirement.id(), tenantId));

        if (!isValidRuleJson(ruleJson)) {
            return new UpdateResult.InvalidRuleJson();
        }

        UUID actor = authorizationContext.currentUserId();
        try {
            requirement.update(groupCode, minCredits, minCourses, ruleJson);
        } catch (IllegalArgumentException e) {
            return new UpdateResult.InvalidData(e.getMessage());
        }
        requirementRepository.save(requirement);
        auditLogWriter.write(tenantId, actor, "CURRICULUM_REQUIREMENT_UPDATED", "catalog.curriculum_requirements",
            requirement.id(), Map.of("groupCode", groupCode));
        return new UpdateResult.Success();
    }

    public sealed interface AddCourseResult {
        record Success(UUID linkId) implements AddCourseResult {
        }

        record RequirementNotFound() implements AddCourseResult {
        }

        record CourseNotFound() implements AddCourseResult {
        }

        record CourseTenantMismatch() implements AddCourseResult {
        }

        record DuplicateMapping() implements AddCourseResult {
        }
    }

    @Transactional
    public AddCourseResult addCourse(UUID requirementId, UUID courseId, RequirementRole requirementRole) {
        Optional<CurriculumRequirement> maybeRequirement = requirementRepository.findById(requirementId);
        if (maybeRequirement.isEmpty()) {
            return new AddCourseResult.RequirementNotFound();
        }
        CurriculumRequirement requirement = maybeRequirement.get();
        UUID tenantId = curriculumRepository.findById(requirement.curriculumId()).map(Curriculum::tenantId).orElseThrow();
        authorizationContext.assertCan(MANAGE_PERMISSION, ResourceRef.of("curriculum_requirement", requirement.id(), tenantId));

        Optional<Course> course = courseRepository.findById(courseId);
        if (course.isEmpty()) {
            return new AddCourseResult.CourseNotFound();
        }
        if (!course.get().tenantId().equals(tenantId)) {
            return new AddCourseResult.CourseTenantMismatch();
        }

        UUID actor = authorizationContext.currentUserId();
        CurriculumRequirementCourse link = new CurriculumRequirementCourse(UUID.randomUUID(), tenantId, requirementId,
            courseId, requirementRole);
        try {
            requirementCourseInsertGuard.insert(link);
        } catch (DataIntegrityViolationException e) {
            return new AddCourseResult.DuplicateMapping();
        }
        auditLogWriter.write(tenantId, actor, "CURRICULUM_REQUIREMENT_COURSE_ADDED",
            "catalog.curriculum_requirement_courses", link.id(),
            Map.of("requirementId", requirementId, "courseId", courseId, "requirementRole", requirementRole.name()));
        return new AddCourseResult.Success(link.id());
    }

    public enum RemoveCourseResult {SUCCESS, NOT_FOUND}

    @Transactional
    public RemoveCourseResult removeCourse(UUID requirementId, UUID courseId) {
        Optional<CurriculumRequirementCourse> maybeLink =
            requirementCourseRepository.findByRequirementIdAndCourseId(requirementId, courseId);
        if (maybeLink.isEmpty()) {
            return RemoveCourseResult.NOT_FOUND;
        }
        CurriculumRequirementCourse link = maybeLink.get();
        authorizationContext.assertCan(MANAGE_PERMISSION, ResourceRef.of("curriculum_requirement", requirementId, link.tenantId()));

        UUID actor = authorizationContext.currentUserId();
        requirementCourseRepository.delete(link);
        auditLogWriter.write(link.tenantId(), actor, "CURRICULUM_REQUIREMENT_COURSE_REMOVED",
            "catalog.curriculum_requirement_courses", link.id(), Map.of("requirementId", requirementId, "courseId", courseId));
        return RemoveCourseResult.SUCCESS;
    }

    @Transactional(readOnly = true)
    public List<CurriculumRequirementCourse> listCourses(UUID requirementId) {
        CurriculumRequirement requirement = requirementRepository.findById(requirementId)
            .orElseThrow(() -> new IllegalArgumentException("requirement not found"));
        UUID tenantId = curriculumRepository.findById(requirement.curriculumId()).map(Curriculum::tenantId).orElseThrow();
        authorizationContext.assertCan(VIEW_PERMISSION, ResourceRef.of("curriculum_requirement", requirement.id(), tenantId));
        return requirementCourseRepository.findByRequirementId(requirementId);
    }
}
