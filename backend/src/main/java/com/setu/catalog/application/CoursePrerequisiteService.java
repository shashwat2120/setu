package com.setu.catalog.application;

import com.setu.catalog.domain.Course;
import com.setu.catalog.domain.CoursePrerequisite;
import com.setu.catalog.domain.PrerequisiteRuleType;
import com.setu.catalog.infrastructure.CoursePrerequisiteInsertGuard;
import com.setu.catalog.infrastructure.CoursePrerequisiteRepository;
import com.setu.catalog.infrastructure.CourseRepository;
import com.setu.shared.authorization.AuthorizationContext;
import com.setu.shared.authorization.ResourceRef;
import com.setu.shared.governance.AuditLogWriter;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Course prerequisite directed-graph edges (Day 7 §7). An edge
 * {@code (course_id, prerequisite_course_id)} means "course_id REQUIRES
 * prerequisite_course_id". Cycle prevention is a bounded graph walk
 * ({@link #MAX_TRAVERSED_NODES} cap so corrupted/huge graphs can't spin
 * forever), the same defensive shape as
 * {@code institution.AcademicOrganizationService}'s hierarchy-cycle check,
 * generalized from a single parent chain to a general DAG since one course
 * may have many prerequisites. Student-side prerequisite EVALUATION
 * (checking whether a specific student satisfies a course's prerequisites)
 * is explicitly Day 8 enrollment scope, not implemented here.
 * <p>
 * {@code @NamedInterface} on this class (Day 8 §13 ceremony): caller is
 * {@code enrollment.application.EnrollmentService}, which must evaluate
 * whether a student satisfies a course's prerequisites WITHOUT duplicating
 * this class's graph-edge storage/traversal (Day 8 explicit instruction).
 * Only {@link #listRequirementsForCourse} is exposed — the bare list of
 * requirement edges for one course, not the {@link CoursePrerequisite}
 * entity and not the cycle-detection machinery itself. No reverse edge from
 * catalog into enrollment exists for this dependency, so the plain
 * {@code @NamedInterface} pattern applies directly. Verified via
 * {@code ModularityTests}.
 */
@org.springframework.modulith.NamedInterface
@Service
public class CoursePrerequisiteService {

    private static final String MANAGE_PERMISSION = "MANAGE_COURSES";
    private static final String VIEW_PERMISSION = "VIEW_TENANT_DATA";
    private static final int MAX_TRAVERSED_NODES = 10_000;

    private final CoursePrerequisiteRepository prerequisiteRepository;
    private final CoursePrerequisiteInsertGuard prerequisiteInsertGuard;
    private final CourseRepository courseRepository;
    private final AuthorizationContext authorizationContext;
    private final AuditLogWriter auditLogWriter;

    public CoursePrerequisiteService(CoursePrerequisiteRepository prerequisiteRepository,
                                      CoursePrerequisiteInsertGuard prerequisiteInsertGuard,
                                      CourseRepository courseRepository, AuthorizationContext authorizationContext,
                                      AuditLogWriter auditLogWriter) {
        this.prerequisiteRepository = prerequisiteRepository;
        this.prerequisiteInsertGuard = prerequisiteInsertGuard;
        this.courseRepository = courseRepository;
        this.authorizationContext = authorizationContext;
        this.auditLogWriter = auditLogWriter;
    }

    public sealed interface AddResult {
        record Success(UUID edgeId) implements AddResult {
        }

        record CourseNotFound() implements AddResult {
        }

        record PrerequisiteCourseNotFound() implements AddResult {
        }

        record TenantMismatch() implements AddResult {
        }

        record SelfReference() implements AddResult {
        }

        record InvalidMinimumGrade() implements AddResult {
        }

        record DuplicateEdge() implements AddResult {
        }

        record WouldCreateCycle() implements AddResult {
        }
    }

    @Transactional
    public AddResult addPrerequisite(UUID courseId, UUID prerequisiteCourseId, PrerequisiteRuleType ruleType,
                                      String minimumGrade) {
        Optional<Course> course = courseRepository.findById(courseId);
        if (course.isEmpty()) {
            return new AddResult.CourseNotFound();
        }
        Optional<Course> prerequisiteCourse = courseRepository.findById(prerequisiteCourseId);
        if (prerequisiteCourse.isEmpty()) {
            return new AddResult.PrerequisiteCourseNotFound();
        }
        UUID tenantId = course.get().tenantId();
        authorizationContext.assertCan(MANAGE_PERMISSION, ResourceRef.of("course", courseId, tenantId));

        if (!prerequisiteCourse.get().tenantId().equals(tenantId)) {
            return new AddResult.TenantMismatch();
        }
        if (courseId.equals(prerequisiteCourseId)) {
            return new AddResult.SelfReference();
        }
        if (minimumGrade != null && !CoursePrerequisite.SUPPORTED_MINIMUM_GRADES.contains(minimumGrade)) {
            return new AddResult.InvalidMinimumGrade();
        }
        if (prerequisiteRepository.findByCourseIdAndPrerequisiteCourseId(courseId, prerequisiteCourseId).isPresent()) {
            return new AddResult.DuplicateEdge();
        }
        if (wouldCreateCycle(courseId, prerequisiteCourseId)) {
            return new AddResult.WouldCreateCycle();
        }

        UUID actor = authorizationContext.currentUserId();
        CoursePrerequisite edge = new CoursePrerequisite(UUID.randomUUID(), tenantId, courseId, prerequisiteCourseId,
            ruleType, minimumGrade);
        try {
            prerequisiteInsertGuard.insert(edge);
        } catch (DataIntegrityViolationException e) {
            return new AddResult.DuplicateEdge();
        }
        auditLogWriter.write(tenantId, actor, "COURSE_PREREQUISITE_ADDED", "catalog.course_prerequisites", edge.id(),
            Map.of("courseId", courseId, "prerequisiteCourseId", prerequisiteCourseId, "ruleType", ruleType.name()));
        return new AddResult.Success(edge.id());
    }

    /**
     * Would adding {@code courseId REQUIRES prerequisiteCourseId} create a
     * cycle? True exactly when {@code prerequisiteCourseId} can already
     * reach {@code courseId} by following existing "requires" edges — i.e.
     * the prerequisite (transitively) already requires the course itself.
     * Bounded BFS: a corrupted/huge existing graph fails safe (treated as a
     * cycle, refusing the new edge) rather than looping unboundedly.
     */
    private boolean wouldCreateCycle(UUID courseId, UUID prerequisiteCourseId) {
        Set<UUID> visited = new HashSet<>();
        ArrayDeque<UUID> queue = new ArrayDeque<>();
        queue.add(prerequisiteCourseId);
        int traversed = 0;
        while (!queue.isEmpty()) {
            UUID current = queue.poll();
            if (current.equals(courseId)) {
                return true;
            }
            if (!visited.add(current)) {
                continue;
            }
            if (++traversed > MAX_TRAVERSED_NODES) {
                return true; // corrupted/oversized graph — refuse rather than risk an undetected cycle
            }
            for (CoursePrerequisite edge : prerequisiteRepository.findByCourseId(current)) {
                queue.add(edge.prerequisiteCourseId());
            }
        }
        return false;
    }

    public enum RemoveResult {SUCCESS, NOT_FOUND}

    @Transactional
    public RemoveResult removePrerequisite(UUID courseId, UUID prerequisiteCourseId) {
        Optional<CoursePrerequisite> maybeEdge =
            prerequisiteRepository.findByCourseIdAndPrerequisiteCourseId(courseId, prerequisiteCourseId);
        if (maybeEdge.isEmpty()) {
            return RemoveResult.NOT_FOUND;
        }
        CoursePrerequisite edge = maybeEdge.get();
        authorizationContext.assertCan(MANAGE_PERMISSION, ResourceRef.of("course", courseId, edge.tenantId()));

        UUID actor = authorizationContext.currentUserId();
        prerequisiteRepository.delete(edge);
        auditLogWriter.write(edge.tenantId(), actor, "COURSE_PREREQUISITE_REMOVED", "catalog.course_prerequisites",
            edge.id(), Map.of("courseId", courseId, "prerequisiteCourseId", prerequisiteCourseId));
        return RemoveResult.SUCCESS;
    }

    @Transactional(readOnly = true)
    public List<CoursePrerequisite> listForCourse(UUID courseId) {
        Course course = courseRepository.findById(courseId).orElseThrow(() -> new IllegalArgumentException("course not found"));
        authorizationContext.assertCan(VIEW_PERMISSION, ResourceRef.of("course", courseId, course.tenantId()));
        return prerequisiteRepository.findByCourseId(courseId);
    }

    /** See this class's javadoc for the full Day 8 §13 cross-module justification. */
    public record PrerequisiteRequirement(UUID prerequisiteCourseId, PrerequisiteRuleType ruleType, String minimumGrade) {
    }

    @Transactional(readOnly = true)
    public List<PrerequisiteRequirement> listRequirementsForCourse(UUID courseId) {
        return prerequisiteRepository.findByCourseId(courseId).stream()
            .map(p -> new PrerequisiteRequirement(p.prerequisiteCourseId(), p.ruleType(), p.minimumGrade()))
            .toList();
    }
}
