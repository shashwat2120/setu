package com.setu.institution.application;

import com.setu.identity.TestAuthentication;
import com.setu.identity.application.UserRegistrationService;
import com.setu.identity.domain.RoleCode;
import com.setu.identity.domain.UserRole;
import com.setu.identity.infrastructure.RoleRepository;
import com.setu.identity.infrastructure.UserRoleRepository;
import com.setu.institution.domain.AcademicOrganization;
import com.setu.institution.domain.College;
import com.setu.institution.domain.Department;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Day 5's required test matrix items 1-7, 11-14. Runs against the real Postgres. */
// Not @Transactional (Sprint 5D hardening): several Day 7 fixture methods now insert through a REQUIRES_NEW guard
// (see e.g. BuildingInsertGuard), so an uncommitted @Transactional test transaction's own fixture rows would be
// invisible to that guard's separate connection — the same lesson already applied across this codebase's other
// REQUIRES_NEW-touching test classes.
@SpringBootTest
class InstitutionAuthorizationTests {

    @Autowired
    CollegeDirectoryService collegeDirectoryService; // bootstrap-only, unauthenticated — see its own javadoc
    @Autowired
    CollegeService collegeService;
    @Autowired
    CampusService campusService;
    @Autowired
    AcademicOrganizationService organizationService;
    @Autowired
    DepartmentService departmentService;
    @Autowired
    UserRegistrationService userRegistrationService;
    @Autowired
    UserRoleRepository userRoleRepository;
    @Autowired
    RoleRepository roleRepository;
    @Autowired
    JdbcTemplate jdbc;

    @AfterEach
    void clearSecurityContext() {
        TestAuthentication.clear();
    }

    private UUID newTenant() {
        return collegeDirectoryService.provisionCollege("I" + UUID.randomUUID().toString().substring(0, 8),
            "Institution Test College", "Asia/Kolkata");
    }

    private UUID newUser(String prefix) {
        var outcome = (UserRegistrationService.Outcome.Created)
            userRegistrationService.register(prefix + "-" + UUID.randomUUID() + "@example.com");
        return outcome.userId();
    }

    private UUID newCollegeAdmin(UUID tenantId) {
        UUID admin = newUser("college-admin");
        UUID roleId = roleRepository.findByCode(RoleCode.COLLEGE_ADMIN).orElseThrow().id();
        userRoleRepository.save(new UserRole(UUID.randomUUID(), admin, roleId, tenantId, Instant.now(), null));
        return admin;
    }

    private UUID newSuperAdmin() {
        UUID admin = newUser("super-admin");
        UUID roleId = roleRepository.findByCode(RoleCode.SUPER_ADMIN).orElseThrow().id();
        userRoleRepository.save(new UserRole(UUID.randomUUID(), admin, roleId, null, Instant.now(), null));
        return admin;
    }

    private UUID newStudent(UUID tenantId) {
        UUID student = newUser("student");
        UUID roleId = roleRepository.findByCode(RoleCode.STUDENT).orElseThrow().id();
        userRoleRepository.save(new UserRole(UUID.randomUUID(), student, roleId, tenantId, Instant.now(), null));
        return student;
    }

    // --- 1. College A user cannot access College B ---
    @Test
    void collegeAUserCannotAccessCollegeB() {
        UUID collegeA = newTenant();
        UUID collegeB = newTenant();
        UUID adminOfA = newCollegeAdmin(collegeA);
        TestAuthentication.authenticateAs(adminOfA);

        assertThatThrownBy(() -> collegeService.get(collegeB))
            .isInstanceOf(AccessDeniedException.class);
    }

    // --- 2. Campus from wrong tenant is denied ---
    @Test
    void campusFromWrongTenantIsDenied_evenWithKnownUuid() {
        UUID collegeA = newTenant();
        UUID collegeB = newTenant();
        UUID adminOfB = newCollegeAdmin(collegeB);
        UUID adminOfA = newCollegeAdmin(collegeA);
        TestAuthentication.authenticateAs(adminOfA);
        var campusResult = (CampusService.CreateResult.Success) campusService.create(collegeA, "MAIN", "Main Campus");
        TestAuthentication.clear();

        TestAuthentication.authenticateAs(adminOfB);
        assertThatThrownBy(() -> campusService.get(campusResult.campusId()))
            .isInstanceOf(AccessDeniedException.class);
    }

    // --- 3. Department from wrong tenant is denied ---
    @Test
    void departmentFromWrongTenantIsDenied() {
        UUID collegeA = newTenant();
        UUID collegeB = newTenant();
        UUID adminOfA = newCollegeAdmin(collegeA);
        UUID adminOfB = newCollegeAdmin(collegeB);
        TestAuthentication.authenticateAs(adminOfA);
        var campus = (CampusService.CreateResult.Success) campusService.create(collegeA, "C1", "Campus One");
        var org = (AcademicOrganizationService.CreateResult.Success)
            organizationService.create(campus.campusId(), null, "Faculty of Engineering", "FACULTY");
        var dept = (DepartmentService.CreateResult.Success)
            departmentService.create(org.organizationId(), "CS", "Computer Science");
        TestAuthentication.clear();

        TestAuthentication.authenticateAs(adminOfB);
        assertThatThrownBy(() -> departmentService.get(dept.departmentId()))
            .isInstanceOf(AccessDeniedException.class);
    }

    // --- 4. Organization hierarchy cannot cross tenants ---
    @Test
    void organizationHierarchyCannotCrossTenants() {
        UUID collegeA = newTenant();
        UUID collegeB = newTenant();
        UUID adminOfA = newCollegeAdmin(collegeA);
        UUID adminOfB = newCollegeAdmin(collegeB);

        TestAuthentication.authenticateAs(adminOfA);
        var campusA = (CampusService.CreateResult.Success) campusService.create(collegeA, "CA", "Campus A");
        var parentInA = (AcademicOrganizationService.CreateResult.Success)
            organizationService.create(campusA.campusId(), null, "Faculty A", "FACULTY");
        TestAuthentication.clear();

        TestAuthentication.authenticateAs(adminOfB);
        var campusB = (CampusService.CreateResult.Success) campusService.create(collegeB, "CB", "Campus B");
        var result = organizationService.create(campusB.campusId(), parentInA.organizationId(), "Dept B", "DEPARTMENT");

        assertThat(result).isInstanceOf(AcademicOrganizationService.CreateResult.ParentInDifferentTenant.class);
    }

    // --- 5. Organization cannot be its own parent ---
    @Test
    void organizationCannotBeItsOwnParent() {
        UUID college = newTenant();
        UUID admin = newCollegeAdmin(college);
        TestAuthentication.authenticateAs(admin);
        var campus = (CampusService.CreateResult.Success) campusService.create(college, "C1", "Campus One");
        var org = (AcademicOrganizationService.CreateResult.Success)
            organizationService.create(campus.campusId(), null, "Faculty", "FACULTY");

        var result = organizationService.setParent(org.organizationId(), org.organizationId());

        assertThat(result).isInstanceOf(AcademicOrganizationService.ReparentResult.SelfParenting.class);
    }

    // --- 6. Organization hierarchy cannot contain cycles ---
    @Test
    void organizationHierarchyCannotContainCycles() {
        UUID college = newTenant();
        UUID admin = newCollegeAdmin(college);
        TestAuthentication.authenticateAs(admin);
        var campus = (CampusService.CreateResult.Success) campusService.create(college, "C1", "Campus One");
        var top = (AcademicOrganizationService.CreateResult.Success)
            organizationService.create(campus.campusId(), null, "Top", "FACULTY");
        var mid = (AcademicOrganizationService.CreateResult.Success)
            organizationService.create(campus.campusId(), top.organizationId(), "Mid", "SCHOOL");
        var bottom = (AcademicOrganizationService.CreateResult.Success)
            organizationService.create(campus.campusId(), mid.organizationId(), "Bottom", "DEPARTMENT");

        // Attempt to make Top a child of Bottom, closing Top -> Mid -> Bottom -> Top.
        var result = organizationService.setParent(top.organizationId(), bottom.organizationId());

        assertThat(result).isInstanceOf(AcademicOrganizationService.ReparentResult.WouldCreateCycle.class);
    }

    // --- 7. Department code uniqueness ---
    @Test
    void departmentCodeMustBeUniquePerTenant() {
        UUID college = newTenant();
        UUID admin = newCollegeAdmin(college);
        TestAuthentication.authenticateAs(admin);
        var campus = (CampusService.CreateResult.Success) campusService.create(college, "C1", "Campus One");
        var org = (AcademicOrganizationService.CreateResult.Success)
            organizationService.create(campus.campusId(), null, "Faculty", "FACULTY");
        departmentService.create(org.organizationId(), "CS", "Computer Science");

        var duplicate = departmentService.create(org.organizationId(), "CS", "Computer Science Redux");

        assertThat(duplicate).isInstanceOf(DepartmentService.CreateResult.CodeAlreadyInUse.class);
    }

    // --- 11. Unauthorized role cannot mutate institution data ---
    @Test
    void studentCannotCreateCampus() {
        UUID college = newTenant();
        UUID student = newStudent(college);
        TestAuthentication.authenticateAs(student);

        assertThatThrownBy(() -> campusService.create(college, "X", "Unauthorized Campus"))
            .isInstanceOf(AccessDeniedException.class);
    }

    // --- 12. Authorized College Admin can operate within own tenant ---
    @Test
    void collegeAdminCanOperateWithinOwnTenant() {
        UUID college = newTenant();
        UUID admin = newCollegeAdmin(college);
        TestAuthentication.authenticateAs(admin);

        var campus = campusService.create(college, "MAIN", "Main Campus");

        assertThat(campus).isInstanceOf(CampusService.CreateResult.Success.class);
    }

    // --- 13. Super Admin platform permission behavior works correctly ---
    @Test
    void superAdminCanCreateCollegesButStillPassesValidation() {
        UUID superAdmin = newSuperAdmin();
        TestAuthentication.authenticateAs(superAdmin);

        var result = collegeService.create("SA" + UUID.randomUUID().toString().substring(0, 6), "New College", "Asia/Kolkata");
        assertThat(result).isInstanceOf(CollegeService.CreateResult.Success.class);

        // Even SUPER_ADMIN cannot skip a REAL validation rule (invalid status transition).
        UUID collegeId = ((CollegeService.CreateResult.Success) result).collegeId();
        var invalidTransition = collegeService.changeStatus(collegeId, College.CollegeStatus.ACTIVE, "no-op");
        assertThat(invalidTransition).isInstanceOf(CollegeService.StatusChangeResult.InvalidTransition.class);
    }

    @Test
    void collegeAdminCannotChangeCollegeStatus_platformLevelOnly() {
        UUID college = newTenant();
        UUID admin = newCollegeAdmin(college);
        TestAuthentication.authenticateAs(admin);

        assertThatThrownBy(() -> collegeService.changeStatus(college, College.CollegeStatus.SUSPENDED, "test"))
            .isInstanceOf(AccessDeniedException.class);
    }

    // --- Day 16 §super-admin: listAll ---

    @Test
    void superAdminCanListAllColleges() {
        UUID superAdmin = newSuperAdmin();
        TestAuthentication.authenticateAs(superAdmin);
        UUID collegeId = ((CollegeService.CreateResult.Success)
            collegeService.create("LA" + UUID.randomUUID().toString().substring(0, 6), "Listable College", "Asia/Kolkata")).collegeId();

        var all = collegeService.listAll();

        assertThat(all).extracting(College::id).contains(collegeId);
    }

    @Test
    void collegeAdminCannotListAllColleges_platformLevelOnly() {
        UUID college = newTenant();
        UUID admin = newCollegeAdmin(college);
        TestAuthentication.authenticateAs(admin);

        assertThatThrownBy(() -> collegeService.listAll()).isInstanceOf(AccessDeniedException.class);
    }

    // --- 14. Sensitive mutations generate audit records ---
    @Test
    void collegeStatusChangeIsAudited() {
        UUID superAdmin = newSuperAdmin();
        TestAuthentication.authenticateAs(superAdmin);
        UUID collegeId = ((CollegeService.CreateResult.Success)
            collegeService.create("AU" + UUID.randomUUID().toString().substring(0, 6), "Audited College", "Asia/Kolkata")).collegeId();

        var result = collegeService.changeStatus(collegeId, College.CollegeStatus.SUSPENDED, "policy violation");

        assertThat(result).isInstanceOf(CollegeService.StatusChangeResult.Success.class);
        // No explicit flush needed: the class is no longer @Transactional (Sprint 5D hardening — see this file's
        // other note), so changeStatus()'s own commit has already happened by the time this call returns.
        Integer auditCount = jdbc.queryForObject(
            "SELECT count(*) FROM governance.audit_logs WHERE action = 'COLLEGE_STATUS_CHANGED' AND resource_id = ?",
            Integer.class, collegeId);
        assertThat(auditCount).isEqualTo(1);
    }

    @Test
    void departmentCreationIsAudited() {
        UUID college = newTenant();
        UUID admin = newCollegeAdmin(college);
        TestAuthentication.authenticateAs(admin);
        var campus = (CampusService.CreateResult.Success) campusService.create(college, "C1", "Campus One");
        var org = (AcademicOrganizationService.CreateResult.Success)
            organizationService.create(campus.campusId(), null, "Faculty", "FACULTY");

        var dept = (DepartmentService.CreateResult.Success) departmentService.create(org.organizationId(), "MATH", "Mathematics");

        // No explicit flush needed: the class is no longer @Transactional (Sprint 5D hardening — see this file's
        // other note), so create()'s own commit has already happened by the time this call returns.
        Integer auditCount = jdbc.queryForObject(
            "SELECT count(*) FROM governance.audit_logs WHERE action = 'DEPARTMENT_CREATED' AND resource_id = ?",
            Integer.class, dept.departmentId());
        assertThat(auditCount).isEqualTo(1);
    }

    // --- Day 16 §college-admin: listForTenant list endpoints ---

    @Test
    void listForTenant_returnsOrganizationsAndDepartmentsOwnedByThatTenantOnly() {
        UUID collegeA = newTenant();
        UUID collegeB = newTenant();
        UUID adminOfA = newCollegeAdmin(collegeA);
        UUID adminOfB = newCollegeAdmin(collegeB);

        TestAuthentication.authenticateAs(adminOfA);
        var campusA = (CampusService.CreateResult.Success) campusService.create(collegeA, "LA1", "List Campus A");
        var orgA = (AcademicOrganizationService.CreateResult.Success)
            organizationService.create(campusA.campusId(), null, "Faculty of Science", "FACULTY");
        departmentService.create(orgA.organizationId(), "LPHY", "Physics");
        TestAuthentication.clear();

        TestAuthentication.authenticateAs(adminOfB);
        var campusB = (CampusService.CreateResult.Success) campusService.create(collegeB, "LB1", "List Campus B");
        organizationService.create(campusB.campusId(), null, "Faculty of Arts", "FACULTY");
        TestAuthentication.clear();

        TestAuthentication.authenticateAs(adminOfA);
        var orgsForA = organizationService.listForTenant(collegeA);
        var deptsForA = departmentService.listForTenant(collegeA);

        assertThat(orgsForA).extracting(AcademicOrganization::tenantId).containsOnly(collegeA);
        assertThat(orgsForA).extracting(AcademicOrganization::name).contains("Faculty of Science")
            .doesNotContain("Faculty of Arts");
        assertThat(deptsForA).extracting(Department::tenantId).containsOnly(collegeA);
    }

    @Test
    void listForTenant_crossTenantAccessIsDenied() {
        UUID collegeA = newTenant();
        UUID collegeB = newTenant();
        UUID adminOfB = newCollegeAdmin(collegeB);
        TestAuthentication.authenticateAs(adminOfB);

        assertThatThrownBy(() -> organizationService.listForTenant(collegeA)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> departmentService.listForTenant(collegeA)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void hodMustHaveActiveRoleInSameTenant() {
        UUID college = newTenant();
        UUID otherCollege = newTenant();
        UUID admin = newCollegeAdmin(college);
        UUID outsiderUser = newCollegeAdmin(otherCollege); // has a role, but in a DIFFERENT tenant
        TestAuthentication.authenticateAs(admin);
        var campus = (CampusService.CreateResult.Success) campusService.create(college, "C1", "Campus One");
        var org = (AcademicOrganizationService.CreateResult.Success)
            organizationService.create(campus.campusId(), null, "Faculty", "FACULTY");
        var dept = (DepartmentService.CreateResult.Success) departmentService.create(org.organizationId(), "PHYS", "Physics");

        var result = departmentService.assignHod(dept.departmentId(), outsiderUser);

        assertThat(result).isInstanceOf(DepartmentService.AssignHodResult.HodNotAuthorizedInTenant.class);
    }
}
