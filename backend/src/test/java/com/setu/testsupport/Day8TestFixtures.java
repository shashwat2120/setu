package com.setu.testsupport;

import com.setu.catalog.application.AcademicPeriodService;
import com.setu.catalog.domain.AcademicPeriod;
import com.setu.directory.application.StudentProfileService;
import com.setu.enrollment.application.CourseSectionService;
import com.setu.enrollment.application.StudentProgramService;
import com.setu.enrollment.domain.CourseSectionStatus;
import com.setu.identity.TestAuthentication;
import com.setu.identity.application.UserRegistrationService;
import com.setu.identity.domain.RoleCode;
import com.setu.identity.domain.UserRole;
import com.setu.identity.infrastructure.RoleRepository;
import com.setu.identity.infrastructure.UserRoleRepository;
import com.setu.shared.identity.PersonDirectory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Day 8 test fixtures — builds on {@link Day7TestFixtures} for the
 * tenant/department/program/curriculum/course/building/room chain, and adds
 * the Day 8-specific steps: a student with an ACTIVE student_program, an
 * academic period actually in REGISTRATION_OPEN (Day7TestFixtures leaves it
 * PLANNED), and a course section actually transitioned to OPEN
 * (Day7TestFixtures leaves it DRAFT) — both required for
 * EnrollmentService.request's invariant chain to have any chance of passing.
 */
@Component
public class Day8TestFixtures {

    private final Day7TestFixtures day7;
    private final UserRegistrationService userRegistrationService;
    private final RoleRepository roleRepository;
    private final UserRoleRepository userRoleRepository;
    private final PersonDirectory personDirectory;
    private final StudentProfileService studentProfileService;
    private final StudentProgramService studentProgramService;
    private final AcademicPeriodService academicPeriodService;
    private final CourseSectionService courseSectionService;

    public Day8TestFixtures(Day7TestFixtures day7, UserRegistrationService userRegistrationService,
                             RoleRepository roleRepository, UserRoleRepository userRoleRepository,
                             PersonDirectory personDirectory, StudentProfileService studentProfileService,
                             StudentProgramService studentProgramService, AcademicPeriodService academicPeriodService,
                             CourseSectionService courseSectionService) {
        this.day7 = day7;
        this.userRegistrationService = userRegistrationService;
        this.roleRepository = roleRepository;
        this.userRoleRepository = userRoleRepository;
        this.personDirectory = personDirectory;
        this.studentProfileService = studentProfileService;
        this.studentProgramService = studentProgramService;
        this.academicPeriodService = academicPeriodService;
        this.courseSectionService = courseSectionService;
    }

    public record Student(UUID studentId, UUID userId) {
    }

    public Student provisionStudentWithActiveProgram(Day7TestFixtures.Tenant tenant, UUID programId, UUID curriculumId, String prefix) {
        var outcome = (UserRegistrationService.Outcome.Created)
            userRegistrationService.register(prefix + "-" + UUID.randomUUID() + "@example.com");
        UUID studentUserId = outcome.userId();
        UUID roleId = roleRepository.findByCode(RoleCode.STUDENT).orElseThrow().id();
        userRoleRepository.save(new UserRole(UUID.randomUUID(), studentUserId, roleId, tenant.tenantId(), Instant.now(), null));

        TestAuthentication.authenticateAs(tenant.adminUserId());
        UUID studentProfileId;
        try {
            UUID personId = personDirectory.createPerson(studentUserId, prefix + " Legal Name", null, null, null,
                prefix + "@example.com", null, null);
            var admitResult = (StudentProfileService.AdmitResult.Success)
                studentProfileService.admitStudent(tenant.tenantId(), personId, prefix + "-ADM", prefix + "-ROLL", LocalDate.of(2024, 6, 1));
            studentProfileId = admitResult.studentId();
            studentProgramService.assign(studentProfileId, programId, curriculumId, 2024, true, LocalDate.of(2024, 6, 1));
        } finally {
            TestAuthentication.clear();
        }
        return new Student(studentProfileId, studentUserId);
    }

    public UUID provisionOpenPeriod(Day7TestFixtures.Tenant tenant, String prefix) {
        UUID periodId = day7.provisionPeriod(tenant, prefix);
        TestAuthentication.authenticateAs(tenant.adminUserId());
        try {
            academicPeriodService.transitionTo(periodId, AcademicPeriod.PeriodStatus.REGISTRATION_OPEN);
        } finally {
            TestAuthentication.clear();
        }
        return periodId;
    }

    public UUID provisionOpenSection(Day7TestFixtures.Tenant tenant, UUID courseId, UUID periodId, String sectionCode,
                                      int capacity, UUID roomId) {
        UUID sectionId = day7.provisionSection(tenant, courseId, periodId, sectionCode, capacity, roomId);
        TestAuthentication.authenticateAs(tenant.adminUserId());
        try {
            courseSectionService.changeStatus(sectionId, CourseSectionStatus.OPEN);
        } finally {
            TestAuthentication.clear();
        }
        return sectionId;
    }
}
