package com.setu.enrollment.application;

import com.setu.identity.TestAuthentication;
import com.setu.testsupport.Day7TestFixtures;
import com.setu.testsupport.Day8TestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Day 8 §5 waitlist behavior. */
// Not @Transactional (Sprint 5D hardening): several Day 7 fixture methods now insert through a REQUIRES_NEW guard
// (see e.g. BuildingInsertGuard), so an uncommitted @Transactional test transaction's own fixture rows would be
// invisible to that guard's separate connection — the same lesson already applied across this codebase's other
// REQUIRES_NEW-touching test classes.
@SpringBootTest
class WaitlistTests {

    @Autowired
    Day7TestFixtures fixtures;
    @Autowired
    Day8TestFixtures day8Fixtures;
    @Autowired
    EnrollmentService enrollmentService;
    @Autowired
    WaitlistService waitlistService;

    @AfterEach
    void clearSecurityContext() {
        TestAuthentication.clear();
    }

    private record Scenario(Day7TestFixtures.Tenant tenant, UUID sectionId, Day8TestFixtures.Student seatHolder,
                             Day8TestFixtures.Student waiting) {
    }

    private Scenario setUp(String prefix) {
        var tenant = fixtures.provisionTenant(prefix);
        UUID dept = fixtures.provisionDepartment(tenant, prefix + "D");
        UUID programId = fixtures.provisionProgram(tenant, dept, prefix + "P");
        UUID curriculumId = fixtures.provisionCurriculum(tenant, programId, "v1", LocalDate.of(2025, 1, 1), null);
        UUID courseId = fixtures.provisionCourse(tenant, dept, prefix + "C");
        UUID periodId = day8Fixtures.provisionOpenPeriod(tenant, prefix + "PR");
        UUID sectionId = day8Fixtures.provisionOpenSection(tenant, courseId, periodId, "S1", 1, null);
        var seatHolder = day8Fixtures.provisionStudentWithActiveProgram(tenant, programId, curriculumId, prefix + "S1");
        var waiting = day8Fixtures.provisionStudentWithActiveProgram(tenant, programId, curriculumId, prefix + "S2");
        TestAuthentication.authenticateAs(seatHolder.userId());
        enrollmentService.request(seatHolder.studentId(), sectionId);
        TestAuthentication.authenticateAs(waiting.userId());
        enrollmentService.request(waiting.studentId(), sectionId);
        TestAuthentication.clear();
        return new Scenario(tenant, sectionId, seatHolder, waiting);
    }

    @Test
    void waitlistedStudentCanWithdraw() {
        var s = setUp("WL1");
        TestAuthentication.authenticateAs(s.waiting.userId());

        var result = waitlistService.withdraw(s.sectionId, s.waiting.studentId());

        assertThat(result).isEqualTo(WaitlistService.WithdrawResult.SUCCESS);
        assertThat(waitlistService.getForStudent(s.sectionId, s.waiting.studentId()).orElseThrow().status().name()).isEqualTo("WITHDRAWN");
    }

    @Test
    void adminCanListSectionWaitlist() {
        var s = setUp("WL2");
        TestAuthentication.authenticateAs(s.tenant.adminUserId());

        var entries = waitlistService.listForSection(s.sectionId, s.tenant.tenantId());

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).studentId()).isEqualTo(s.waiting.studentId());
        assertThat(entries.get(0).position()).isEqualTo(1);
    }

    @Test
    void dropFreeingSeatPromotesWaitlistedStudent() {
        var s = setUp("WL3");
        TestAuthentication.authenticateAs(s.seatHolder.userId());
        UUID holderEnrollmentId = enrollmentService.listForStudent(s.seatHolder.studentId(),
            org.springframework.data.domain.PageRequest.of(0, 10)).getContent().get(0).id();

        enrollmentService.drop(holderEnrollmentId, "freeing seat");
        TestAuthentication.authenticateAs(s.waiting.userId());

        var promotedEntry = waitlistService.getForStudent(s.sectionId, s.waiting.studentId()).orElseThrow();
        assertThat(promotedEntry.status().name()).isEqualTo("CONVERTED");
        var promotedEnrollment = enrollmentService.listForStudent(s.waiting.studentId(), org.springframework.data.domain.PageRequest.of(0, 10))
            .getContent().stream().filter(e -> e.sectionId().equals(s.sectionId)).findFirst().orElseThrow();
        assertThat(promotedEnrollment.status().name()).isEqualTo("ENROLLED");
    }
}
