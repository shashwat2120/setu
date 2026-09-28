package com.setu.testsupport;

import com.setu.directory.application.DirectoryService;
import com.setu.guardianaccess.application.GuardianAccessService;
import com.setu.identity.TestAuthentication;
import com.setu.identity.application.UserRegistrationService;
import com.setu.identity.domain.Person;
import com.setu.learning.application.AssignmentService;
import com.setu.learning.application.CourseModuleService;
import com.setu.learning.application.LessonService;
import com.setu.learning.domain.LessonType;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Day 10 test fixtures — builds on {@link Day7TestFixtures}/{@link
 * Day8TestFixtures}/{@link Day9TestFixtures} for tenant/section/faculty/
 * student, and adds the LMS-specific provisioning: a module/lesson pair,
 * an assignment, and a guardian holding an active {@code VIEW_LEARNING_DATA}
 * delegation for an existing student.
 */
@Component
public class Day10TestFixtures {

    private final CourseModuleService courseModuleService;
    private final LessonService lessonService;
    private final AssignmentService assignmentService;
    private final DirectoryService directoryService;
    private final GuardianAccessService guardianAccessService;
    private final UserRegistrationService userRegistrationService;
    private final com.setu.identity.infrastructure.PersonRepository personRepository;
    private final com.setu.identity.infrastructure.RoleRepository roleRepository;
    private final com.setu.identity.infrastructure.UserRoleRepository userRoleRepository;

    public Day10TestFixtures(CourseModuleService courseModuleService, LessonService lessonService,
                              AssignmentService assignmentService, DirectoryService directoryService,
                              GuardianAccessService guardianAccessService, UserRegistrationService userRegistrationService,
                              com.setu.identity.infrastructure.PersonRepository personRepository,
                              com.setu.identity.infrastructure.RoleRepository roleRepository,
                              com.setu.identity.infrastructure.UserRoleRepository userRoleRepository) {
        this.courseModuleService = courseModuleService;
        this.lessonService = lessonService;
        this.assignmentService = assignmentService;
        this.directoryService = directoryService;
        this.guardianAccessService = guardianAccessService;
        this.userRegistrationService = userRegistrationService;
        this.personRepository = personRepository;
        this.roleRepository = roleRepository;
        this.userRoleRepository = userRoleRepository;
    }

    /** Creates a module as the given faculty (must be actively assigned to sectionId, or the admin userId). */
    public UUID provisionModule(UUID actingUserId, UUID sectionId, String title, int sequence) {
        TestAuthentication.authenticateAs(actingUserId);
        try {
            var result = (CourseModuleService.CreateResult.Success) courseModuleService.create(sectionId, title, sequence);
            return result.moduleId();
        } finally {
            TestAuthentication.clear();
        }
    }

    public UUID provisionLesson(UUID actingUserId, UUID moduleId, String title, int sequence) {
        TestAuthentication.authenticateAs(actingUserId);
        try {
            var result = (LessonService.CreateResult.Success)
                lessonService.create(moduleId, title, LessonType.VIDEO, "s3://bucket/" + UUID.randomUUID(), sequence, 10);
            return result.lessonId();
        } finally {
            TestAuthentication.clear();
        }
    }

    public record OpenAssignment(UUID assignmentId) {
    }

    /** Creates and publishes an assignment (DRAFT -> PUBLISHED) as the given faculty/admin. */
    public UUID provisionPublishedAssignment(UUID actingUserId, UUID sectionId, String title, Instant dueAt, String latePolicyJson) {
        TestAuthentication.authenticateAs(actingUserId);
        try {
            var created = (AssignmentService.CreateResult.Success)
                assignmentService.create(sectionId, title, new BigDecimal("100"), dueAt, latePolicyJson);
            assignmentService.publish(created.assignmentId());
            return created.assignmentId();
        } finally {
            TestAuthentication.clear();
        }
    }

    public record Guardian(UUID guardianUserId, UUID guardianId) {
    }

    /** Registers a fresh guardian user + directory.guardians row (not yet linked to any student — grant separately). */
    public Guardian provisionGuardian(Day7TestFixtures.Tenant tenant, String prefix) {
        var outcome = (UserRegistrationService.Outcome.Created)
            userRegistrationService.register(prefix + "-" + UUID.randomUUID() + "@example.com");
        UUID guardianUserId = outcome.userId();
        var roleId = roleRepository.findByCode(com.setu.identity.domain.RoleCode.GUARDIAN).orElseThrow().id();
        userRoleRepository.save(new com.setu.identity.domain.UserRole(
            UUID.randomUUID(), guardianUserId, roleId, tenant.tenantId(), Instant.now(), null));

        TestAuthentication.authenticateAs(tenant.adminUserId());
        try {
            Person guardianPerson = Person.withLegalName(UUID.randomUUID(), guardianUserId, prefix + " Guardian", Instant.now());
            personRepository.save(guardianPerson);
            UUID guardianId = directoryService.provisionGuardian(tenant.tenantId(), guardianPerson.id(), guardianUserId);
            return new Guardian(guardianUserId, guardianId);
        } finally {
            TestAuthentication.clear();
        }
    }

    /** Grants the guardian VIEW_LEARNING_DATA for one specific student (as the tenant admin, the only legitimate granter — Day 6 §8). */
    public void grantLearningDataAccess(Day7TestFixtures.Tenant tenant, Guardian guardian, UUID studentProfileId) {
        TestAuthentication.authenticateAs(tenant.adminUserId());
        try {
            guardianAccessService.grantAccess(studentProfileId, guardian.guardianId(), null, Set.of("VIEW_LEARNING_DATA"));
        } finally {
            TestAuthentication.clear();
        }
    }
}
