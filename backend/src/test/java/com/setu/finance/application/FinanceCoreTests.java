package com.setu.finance.application;

import com.setu.identity.TestAuthentication;
import com.setu.identity.domain.RoleCode;
import com.setu.testsupport.Day7TestFixtures;
import com.setu.testsupport.Day8TestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Day 12 §Finance: fee_structures/fee_components/student_charges/student_accounts
 * — CRUD, authorization (COLLEGE_ADMIN full, STUDENT own-read-only,
 * FACULTY none), tenant isolation, validation.
 * <p>
 * Deliberately NOT {@code @Transactional} — {@code FeeComponentInsertGuard}/
 * {@code StudentAccountInsertGuard} use {@code REQUIRES_NEW}, so an
 * uncommitted test transaction's own fixture rows would be invisible to
 * those guards' separate connection (the same lesson applied throughout
 * this codebase's other REQUIRES_NEW-touching test classes).
 */
@SpringBootTest
class FinanceCoreTests {

    @Autowired
    Day7TestFixtures fixtures;
    @Autowired
    Day8TestFixtures day8Fixtures;
    @Autowired
    FeeStructureService feeStructureService;
    @Autowired
    FeeComponentService feeComponentService;
    @Autowired
    StudentChargeService studentChargeService;
    @Autowired
    StudentAccountService studentAccountService;

    @AfterEach
    void clearSecurityContext() {
        TestAuthentication.clear();
    }

    private record Scenario(Day7TestFixtures.Tenant tenant, UUID programId, UUID curriculumId) {
    }

    private Scenario setUp(String prefix) {
        var tenant = fixtures.provisionTenant(prefix);
        UUID dept = fixtures.provisionDepartment(tenant, prefix + "D");
        UUID programId = fixtures.provisionProgram(tenant, dept, prefix + "P");
        UUID curriculumId = fixtures.provisionCurriculum(tenant, programId, "v1", java.time.LocalDate.of(2025, 1, 1), null);
        return new Scenario(tenant, programId, curriculumId);
    }

    @Test
    void collegeAdminCanCreateFeeStructureAndComponent() {
        var s = setUp("FIN1");
        TestAuthentication.authenticateAs(s.tenant.adminUserId());

        var structResult = feeStructureService.create(s.tenant.tenantId(), s.programId, "Tuition 2025", null);
        assertThat(structResult).isInstanceOf(FeeStructureService.CreateResult.Success.class);
        UUID structureId = ((FeeStructureService.CreateResult.Success) structResult).feeStructureId();

        var compResult = feeComponentService.create(structureId, "TUITION", new BigDecimal("50000"), "INR");
        assertThat(compResult).isInstanceOf(FeeComponentService.CreateResult.Success.class);
    }

    @Test
    void facultyCannotManageFinance() {
        var s = setUp("FIN2");
        UUID facultyUserId = fixtures.provisionUserWithRole(s.tenant.tenantId(), RoleCode.FACULTY, "FIN2-F");
        TestAuthentication.authenticateAs(facultyUserId);

        assertThatThrownBy(() -> feeStructureService.create(s.tenant.tenantId(), null, "Tuition", null))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void duplicateFeeComponentCodeInSameStructureIsRejected() {
        var s = setUp("FIN3");
        TestAuthentication.authenticateAs(s.tenant.adminUserId());
        UUID structureId = ((FeeStructureService.CreateResult.Success)
            feeStructureService.create(s.tenant.tenantId(), null, "Fees", null)).feeStructureId();
        feeComponentService.create(structureId, "LAB", new BigDecimal("1000"), "INR");

        var second = feeComponentService.create(structureId, "LAB", new BigDecimal("2000"), "INR");

        assertThat(second).isInstanceOf(FeeComponentService.CreateResult.CodeAlreadyInUse.class);
    }

    @Test
    void invalidFeeComponentAmountIsRejected() {
        var s = setUp("FIN4");
        TestAuthentication.authenticateAs(s.tenant.adminUserId());
        UUID structureId = ((FeeStructureService.CreateResult.Success)
            feeStructureService.create(s.tenant.tenantId(), null, "Fees", null)).feeStructureId();

        var result = feeComponentService.create(structureId, "BAD", new BigDecimal("-5"), "INR");

        assertThat(result).isInstanceOf(FeeComponentService.CreateResult.InvalidData.class);
    }

    @Test
    void creatingChargeIncreasesStudentAccountBalance() {
        var s = setUp("FIN5");
        var student = day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId, s.curriculumId, "FIN5S");
        TestAuthentication.authenticateAs(s.tenant.adminUserId());
        UUID structureId = ((FeeStructureService.CreateResult.Success)
            feeStructureService.create(s.tenant.tenantId(), null, "Fees", null)).feeStructureId();
        UUID componentId = ((FeeComponentService.CreateResult.Success)
            feeComponentService.create(structureId, "TUITION", new BigDecimal("30000"), "INR")).componentId();

        var chargeResult = studentChargeService.create(student.studentId(), componentId, null);
        assertThat(chargeResult).isInstanceOf(StudentChargeService.CreateResult.Success.class);

        var account = studentAccountService.get(s.tenant.tenantId(), student.studentId()).orElseThrow();
        assertThat(account.balance()).isEqualByComparingTo("30000");
    }

    @Test
    void studentCanReadOwnChargesButNotOthers() {
        var s = setUp("FIN6");
        var student = day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId, s.curriculumId, "FIN6S");
        var otherStudent = day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId, s.curriculumId, "FIN6O");
        TestAuthentication.authenticateAs(s.tenant.adminUserId());
        UUID structureId = ((FeeStructureService.CreateResult.Success)
            feeStructureService.create(s.tenant.tenantId(), null, "Fees", null)).feeStructureId();
        UUID componentId = ((FeeComponentService.CreateResult.Success)
            feeComponentService.create(structureId, "TUITION", new BigDecimal("1000"), "INR")).componentId();
        studentChargeService.create(student.studentId(), componentId, null);
        TestAuthentication.clear();

        TestAuthentication.authenticateAs(student.userId());
        assertThat(studentChargeService.listForStudent(student.studentId())).hasSize(1);
        assertThatThrownBy(() -> studentChargeService.listForStudent(otherStudent.studentId()))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void crossTenantAdminCannotManageAnotherTenantsFinance() {
        var s1 = setUp("FIN7A");
        var s2 = setUp("FIN7B");
        TestAuthentication.authenticateAs(s2.tenant.adminUserId());

        assertThatThrownBy(() -> feeStructureService.create(s1.tenant.tenantId(), null, "Fees", null))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void waivingChargeTransitionsStatusAndRejectsSecondAttempt() {
        var s = setUp("FIN8");
        var student = day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId, s.curriculumId, "FIN8S");
        TestAuthentication.authenticateAs(s.tenant.adminUserId());
        UUID structureId = ((FeeStructureService.CreateResult.Success)
            feeStructureService.create(s.tenant.tenantId(), null, "Fees", null)).feeStructureId();
        UUID componentId = ((FeeComponentService.CreateResult.Success)
            feeComponentService.create(structureId, "TUITION", new BigDecimal("500"), "INR")).componentId();
        UUID chargeId = ((StudentChargeService.CreateResult.Success)
            studentChargeService.create(student.studentId(), componentId, null)).chargeId();

        var first = studentChargeService.markWaived(chargeId);
        assertThat(first).isInstanceOf(StudentChargeService.TransitionResult.Success.class);

        var second = studentChargeService.markWaived(chargeId);
        assertThat(second).isInstanceOf(StudentChargeService.TransitionResult.InvalidTransition.class);
    }
}
