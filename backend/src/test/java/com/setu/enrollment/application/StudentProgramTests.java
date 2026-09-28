package com.setu.enrollment.application;

import com.setu.catalog.application.CurriculumService;
import com.setu.catalog.application.ProgramService;
import com.setu.directory.application.StudentProfileService;
import com.setu.enrollment.domain.StudentProgramStatus;
import com.setu.identity.TestAuthentication;
import com.setu.identity.application.PersonService;
import com.setu.identity.application.UserRegistrationService;
import com.setu.identity.domain.RoleCode;
import com.setu.identity.domain.UserRole;
import com.setu.identity.infrastructure.RoleRepository;
import com.setu.identity.infrastructure.UserRoleRepository;
import com.setu.institution.application.AcademicOrganizationService;
import com.setu.institution.application.CampusService;
import com.setu.institution.application.CollegeDirectoryService;
import com.setu.institution.application.CollegeService;
import com.setu.institution.application.DepartmentService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Day 6 test matrix category E: student program. */
// Not @Transactional (Sprint 5D hardening): several Day 7 fixture methods now insert through a REQUIRES_NEW guard
// (see e.g. BuildingInsertGuard), so an uncommitted @Transactional test transaction's own fixture rows would be
// invisible to that guard's separate connection — the same lesson already applied across this codebase's other
// REQUIRES_NEW-touching test classes.
@SpringBootTest
class StudentProgramTests {

    @Autowired
    StudentProgramService studentProgramService;
    @Autowired
    StudentProfileService studentProfileService;
    @Autowired
    ProgramService programService;
    @Autowired
    CurriculumService curriculumService;
    @Autowired
    CollegeService collegeService;
    @Autowired
    CampusService campusService;
    @Autowired
    AcademicOrganizationService organizationService;
    @Autowired
    DepartmentService departmentService;
    @Autowired
    PersonService personService;
    @Autowired
    CollegeDirectoryService collegeDirectoryService;
    @Autowired
    UserRegistrationService userRegistrationService;
    @Autowired
    UserRoleRepository userRoleRepository;
    @Autowired
    RoleRepository roleRepository;

    @AfterEach
    void clearSecurityContext() {
        TestAuthentication.clear();
    }

    private record Fixture(UUID tenantId, UUID adminUserId, UUID programId, UUID curriculumId, UUID studentId) {
    }

    private UUID newSuperAdmin() {
        var outcome = (UserRegistrationService.Outcome.Created)
            userRegistrationService.register("sp-super-" + UUID.randomUUID() + "@example.com");
        UUID roleId = roleRepository.findByCode(RoleCode.SUPER_ADMIN).orElseThrow().id();
        userRoleRepository.save(new UserRole(UUID.randomUUID(), outcome.userId(), roleId, null, Instant.now(), null));
        return outcome.userId();
    }

    private Fixture newFixture() {
        UUID tenant = collegeDirectoryService.provisionCollege("SP" + UUID.randomUUID().toString().substring(0, 7),
            "Student Program Test College", "Asia/Kolkata");
        var outcome = (UserRegistrationService.Outcome.Created)
            userRegistrationService.register("sp-admin-" + UUID.randomUUID() + "@example.com");
        UUID admin = outcome.userId();
        UUID roleId = roleRepository.findByCode(RoleCode.COLLEGE_ADMIN).orElseThrow().id();
        userRoleRepository.save(new UserRole(UUID.randomUUID(), admin, roleId, tenant, Instant.now(), null));

        TestAuthentication.authenticateAs(admin);
        var campus = (CampusService.CreateResult.Success) campusService.create(tenant, "C1", "Main Campus");
        var org = (AcademicOrganizationService.CreateResult.Success)
            organizationService.create(campus.campusId(), null, "Faculty", "FACULTY");
        var dept = (DepartmentService.CreateResult.Success) departmentService.create(org.organizationId(), "CS", "CS Dept");
        UUID programId = ((ProgramService.CreateResult.Success)
            programService.create(dept.departmentId(), "PROG-" + UUID.randomUUID().toString().substring(0, 6), "Test Program", null, null, null)).programId();
        UUID curriculumId = ((CurriculumService.CreateResult.Success) curriculumService.createReference(
            programId, "Curriculum v1", "v1", LocalDate.of(2020, 1, 1), null)).curriculumId();
        UUID person = personService.createPerson(null, "Program Student", null, null, null, null, null, null);
        UUID studentId = ((StudentProfileService.AdmitResult.Success)
            studentProfileService.admitStudent(tenant, person, "SADM-" + UUID.randomUUID().toString().substring(0, 6), null, LocalDate.of(2026, 6, 1))).studentId();
        TestAuthentication.clear();

        return new Fixture(tenant, admin, programId, curriculumId, studentId);
    }

    @Test
    void validAssignment() {
        Fixture f = newFixture();
        TestAuthentication.authenticateAs(f.adminUserId());

        var result = studentProgramService.assign(f.studentId(), f.programId(), f.curriculumId(), 2026, true, LocalDate.of(2026, 6, 1));

        assertThat(result).isInstanceOf(StudentProgramService.AssignResult.Success.class);
    }

    @Test
    void crossTenantAssignmentIsDenied() {
        Fixture fA = newFixture();
        Fixture fB = newFixture();
        TestAuthentication.authenticateAs(fA.adminUserId());

        var result = studentProgramService.assign(fA.studentId(), fB.programId(), fB.curriculumId(), 2026, true, LocalDate.of(2026, 6, 1));

        assertThat(result).isInstanceOf(StudentProgramService.AssignResult.TenantMismatch.class);
    }

    @Test
    void duplicateActivePrimaryProgramIsRejected() {
        Fixture f = newFixture();
        TestAuthentication.authenticateAs(f.adminUserId());
        studentProgramService.assign(f.studentId(), f.programId(), f.curriculumId(), 2026, true, LocalDate.of(2026, 6, 1));

        UUID secondProgramId = ((ProgramService.CreateResult.Success) programService.create(
            departmentIdFor(f), "PROG2-" + UUID.randomUUID().toString().substring(0, 6), "Second Program", null, null, null)).programId();
        UUID secondCurriculumId = ((CurriculumService.CreateResult.Success) curriculumService.createReference(
            secondProgramId, "Curriculum v1", "v1", LocalDate.of(2020, 1, 1), null)).curriculumId();

        var result = studentProgramService.assign(f.studentId(), secondProgramId, secondCurriculumId, 2026, true, LocalDate.of(2026, 6, 1));

        assertThat(result).isInstanceOf(StudentProgramService.AssignResult.DuplicateActivePrimaryProgram.class);
    }

    private UUID departmentIdFor(Fixture f) {
        return programService.get(f.programId()).orElseThrow().departmentId();
    }

    @Test
    void secondaryProgramRejectedByDefaultPolicy_thenAllowedWhenDualProgramEnabled() {
        Fixture f = newFixture();
        TestAuthentication.authenticateAs(f.adminUserId());
        studentProgramService.assign(f.studentId(), f.programId(), f.curriculumId(), 2026, true, LocalDate.of(2026, 6, 1));

        UUID secondProgramId = ((ProgramService.CreateResult.Success) programService.create(
            departmentIdFor(f), "PROG3-" + UUID.randomUUID().toString().substring(0, 6), "Secondary Program", null, null, null)).programId();
        UUID secondCurriculumId = ((CurriculumService.CreateResult.Success) curriculumService.createReference(
            secondProgramId, "Curriculum v1", "v1", LocalDate.of(2020, 1, 1), null)).curriculumId();

        // Default policy (allow_dual_program not set): a second ACTIVE
        // non-primary program is rejected.
        var deniedResult = studentProgramService.assign(f.studentId(), secondProgramId, secondCurriculumId, 2026, false, LocalDate.of(2026, 6, 1));
        assertThat(deniedResult).isInstanceOf(StudentProgramService.AssignResult.SecondaryProgramNotAllowedByPolicy.class);
        TestAuthentication.clear();

        // A SUPER_ADMIN enables the tenant policy toggle.
        TestAuthentication.authenticateAs(newSuperAdmin());
        collegeService.updateSettings(f.tenantId(), "{\"allow_dual_program\": true}");
        TestAuthentication.clear();

        TestAuthentication.authenticateAs(f.adminUserId());
        var allowedResult = studentProgramService.assign(f.studentId(), secondProgramId, secondCurriculumId, 2026, false, LocalDate.of(2026, 6, 1));
        assertThat(allowedResult).isInstanceOf(StudentProgramService.AssignResult.Success.class);
    }

    @Test
    void historyIsPreservedAcrossStatusChanges() {
        Fixture f = newFixture();
        TestAuthentication.authenticateAs(f.adminUserId());
        UUID spId = ((StudentProgramService.AssignResult.Success)
            studentProgramService.assign(f.studentId(), f.programId(), f.curriculumId(), 2026, true, LocalDate.of(2026, 6, 1))).studentProgramId();

        studentProgramService.changeStatus(spId, StudentProgramStatus.ON_HOLD);
        studentProgramService.changeStatus(spId, StudentProgramStatus.ACTIVE);
        studentProgramService.changeStatus(spId, StudentProgramStatus.COMPLETED);

        var history = studentProgramService.history(f.studentId());
        assertThat(history).hasSize(1); // one row throughout — status changed in place, never replaced
        assertThat(history.get(0).status()).isEqualTo(StudentProgramStatus.COMPLETED);

        // Terminal state cannot be re-transitioned — a genuine re-admission needs a NEW row.
        var result = studentProgramService.changeStatus(spId, StudentProgramStatus.ACTIVE);
        assertThat(result).isEqualTo(StudentProgramService.StatusChangeResult.INVALID_TRANSITION);
    }

    @Test
    void unauthorizedRoleCannotAssignPrograms() {
        Fixture f = newFixture();
        var outcome = (UserRegistrationService.Outcome.Created)
            userRegistrationService.register("plain-student-sp-" + UUID.randomUUID() + "@example.com");
        UUID roleId = roleRepository.findByCode(RoleCode.STUDENT).orElseThrow().id();
        userRoleRepository.save(new UserRole(UUID.randomUUID(), outcome.userId(), roleId, f.tenantId(), Instant.now(), null));
        TestAuthentication.authenticateAs(outcome.userId());

        assertThatThrownBy(() -> studentProgramService.assign(f.studentId(), f.programId(), f.curriculumId(), 2026, true, LocalDate.of(2026, 6, 1)))
            .isInstanceOf(AccessDeniedException.class);
    }
}
