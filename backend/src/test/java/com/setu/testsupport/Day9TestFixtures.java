package com.setu.testsupport;

import com.setu.catalog.application.MeetingPatternService;
import com.setu.directory.application.FacultyProfileService;
import com.setu.enrollment.application.EnrollmentService;
import com.setu.enrollment.application.FacultyAssignmentService;
import com.setu.enrollment.domain.FacultyAssignmentRole;
import com.setu.identity.TestAuthentication;
import com.setu.identity.application.UserRegistrationService;
import com.setu.identity.domain.RoleCode;
import com.setu.identity.domain.UserRole;
import com.setu.identity.infrastructure.RoleRepository;
import com.setu.identity.infrastructure.UserRoleRepository;
import com.setu.shared.identity.PersonDirectory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

/**
 * Day 9 test fixtures — builds on {@link Day7TestFixtures}/{@link
 * Day8TestFixtures} for tenant/program/course/section/student, and adds
 * the attendance-specific pieces: a faculty member actively assigned to a
 * section, and a meeting pattern for that section (attendance_sessions.
 * meeting_id references catalog.meeting_patterns, not course_sections
 * directly).
 */
@Component
public class Day9TestFixtures {

    private final UserRegistrationService userRegistrationService;
    private final RoleRepository roleRepository;
    private final UserRoleRepository userRoleRepository;
    private final PersonDirectory personDirectory;
    private final FacultyProfileService facultyProfileService;
    private final FacultyAssignmentService facultyAssignmentService;
    private final MeetingPatternService meetingPatternService;
    private final EnrollmentService enrollmentService;

    public Day9TestFixtures(UserRegistrationService userRegistrationService, RoleRepository roleRepository,
                             UserRoleRepository userRoleRepository, PersonDirectory personDirectory,
                             FacultyProfileService facultyProfileService, FacultyAssignmentService facultyAssignmentService,
                             MeetingPatternService meetingPatternService, EnrollmentService enrollmentService) {
        this.userRegistrationService = userRegistrationService;
        this.roleRepository = roleRepository;
        this.userRoleRepository = userRoleRepository;
        this.personDirectory = personDirectory;
        this.facultyProfileService = facultyProfileService;
        this.facultyAssignmentService = facultyAssignmentService;
        this.meetingPatternService = meetingPatternService;
        this.enrollmentService = enrollmentService;
    }

    public record Faculty(UUID facultyId, UUID userId) {
    }

    public Faculty provisionFacultyAssignedToSection(Day7TestFixtures.Tenant tenant, UUID sectionId, String prefix) {
        var outcome = (UserRegistrationService.Outcome.Created)
            userRegistrationService.register(prefix + "-" + UUID.randomUUID() + "@example.com");
        UUID facultyUserId = outcome.userId();
        UUID roleId = roleRepository.findByCode(RoleCode.FACULTY).orElseThrow().id();
        userRoleRepository.save(new UserRole(UUID.randomUUID(), facultyUserId, roleId, tenant.tenantId(), Day7TestFixtures.roleStartedAt(), null));

        TestAuthentication.authenticateAs(tenant.adminUserId());
        UUID facultyId;
        try {
            UUID personId = personDirectory.createPerson(facultyUserId, prefix + " Legal Name", null, null, null,
                prefix + "@example.com", null, null);
            var createResult = (FacultyProfileService.CreateResult.Success)
                facultyProfileService.create(tenant.tenantId(), personId, prefix + "-EMP", "Lecturer", LocalDate.of(2020, 1, 1));
            facultyId = createResult.facultyId();
            facultyAssignmentService.assign(sectionId, facultyId, FacultyAssignmentRole.PRIMARY,
                new BigDecimal("100"), LocalDate.of(2025, 1, 1), null);
        } finally {
            TestAuthentication.clear();
        }
        return new Faculty(facultyId, facultyUserId);
    }

    public UUID provisionMeetingPattern(Day7TestFixtures.Tenant tenant, UUID sectionId, UUID roomId) {
        TestAuthentication.authenticateAs(tenant.adminUserId());
        try {
            var result = (MeetingPatternService.CreateResult.Success)
                meetingPatternService.create(sectionId, (short) 1, LocalTime.of(9, 0), LocalTime.of(10, 0), roomId, null, null);
            return result.meetingPatternId();
        } finally {
            TestAuthentication.clear();
        }
    }

    /** The student's own bearer-authenticated enrollment request — returns the resulting enrollmentId. */
    public UUID enrollStudent(Day8TestFixtures.Student student, UUID sectionId) {
        TestAuthentication.authenticateAs(student.userId());
        try {
            var result = (EnrollmentService.RequestResult.Enrolled) enrollmentService.request(student.studentId(), sectionId);
            return result.enrollmentId();
        } finally {
            TestAuthentication.clear();
        }
    }
}
