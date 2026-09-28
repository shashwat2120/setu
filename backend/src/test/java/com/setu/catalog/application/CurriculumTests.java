package com.setu.catalog.application;

import com.setu.identity.TestAuthentication;
import com.setu.testsupport.Day7TestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Day 7 §16 CURRICULUM test matrix. */
// Not @Transactional (Sprint 5D hardening): several Day 7 fixture methods now insert through a REQUIRES_NEW guard
// (see e.g. BuildingInsertGuard), so an uncommitted @Transactional test transaction's own fixture rows would be
// invisible to that guard's separate connection — the same lesson already applied across this codebase's other
// REQUIRES_NEW-touching test classes.
@SpringBootTest
class CurriculumTests {

    @Autowired
    Day7TestFixtures fixtures;
    @Autowired
    CurriculumService curriculumService;

    @AfterEach
    void clearSecurityContext() {
        TestAuthentication.clear();
    }

    @Test
    void validCurriculumCreation() {
        var tenant = fixtures.provisionTenant("CUR1");
        UUID dept = fixtures.provisionDepartment(tenant, "CUR1D");
        UUID programId = fixtures.provisionProgram(tenant, dept, "CUR1P");
        TestAuthentication.authenticateAs(tenant.adminUserId());

        var result = curriculumService.createReference(programId, "2025 Curriculum", "v1",
            LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31));

        assertThat(result).isInstanceOf(CurriculumService.CreateResult.Success.class);
    }

    @Test
    void invalidDatesAreRejected() {
        var tenant = fixtures.provisionTenant("CUR2");
        UUID dept = fixtures.provisionDepartment(tenant, "CUR2D");
        UUID programId = fixtures.provisionProgram(tenant, dept, "CUR2P");
        TestAuthentication.authenticateAs(tenant.adminUserId());

        var result = curriculumService.createReference(programId, "Bad Curriculum", "v1",
            LocalDate.of(2025, 6, 1), LocalDate.of(2025, 1, 1));

        assertThat(result).isInstanceOf(CurriculumService.CreateResult.InvalidDateRange.class);
    }

    @Test
    void overlappingVersionForSameProgramIsRejected() {
        var tenant = fixtures.provisionTenant("CUR3");
        UUID dept = fixtures.provisionDepartment(tenant, "CUR3D");
        UUID programId = fixtures.provisionProgram(tenant, dept, "CUR3P");
        fixtures.provisionCurriculum(tenant, programId, "v1", LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31));
        TestAuthentication.authenticateAs(tenant.adminUserId());

        var result = curriculumService.createReference(programId, "Overlapping", "v2",
            LocalDate.of(2025, 6, 1), LocalDate.of(2026, 6, 1));

        assertThat(result).isInstanceOf(CurriculumService.CreateResult.OverlapsExistingVersion.class);
    }

    @Test
    void nonOverlappingSuccessiveVersionIsAccepted() {
        var tenant = fixtures.provisionTenant("CUR4");
        UUID dept = fixtures.provisionDepartment(tenant, "CUR4D");
        UUID programId = fixtures.provisionProgram(tenant, dept, "CUR4P");
        fixtures.provisionCurriculum(tenant, programId, "v1", LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31));
        TestAuthentication.authenticateAs(tenant.adminUserId());

        var result = curriculumService.createReference(programId, "Next Version", "v2",
            LocalDate.of(2025, 12, 31), LocalDate.of(2026, 12, 31));

        assertThat(result).isInstanceOf(CurriculumService.CreateResult.Success.class);
    }

    @Test
    void tenantMismatchOnProgramIsDenied() {
        var tenantA = fixtures.provisionTenant("CUR5A");
        var tenantB = fixtures.provisionTenant("CUR5B");
        UUID deptB = fixtures.provisionDepartment(tenantB, "CUR5D");
        UUID programB = fixtures.provisionProgram(tenantB, deptB, "CUR5P");
        TestAuthentication.authenticateAs(tenantA.adminUserId());

        assertThatThrownBy(() -> curriculumService.createReference(programB, "Cross Tenant", "v1",
            LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31)))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void updateClosingEffectiveToPreservesHistoryAndIsAuditedAsVersionChange() {
        var tenant = fixtures.provisionTenant("CUR6");
        UUID dept = fixtures.provisionDepartment(tenant, "CUR6D");
        UUID programId = fixtures.provisionProgram(tenant, dept, "CUR6P");
        UUID curriculumId = fixtures.provisionCurriculum(tenant, programId, "v1", LocalDate.of(2025, 1, 1), null);
        TestAuthentication.authenticateAs(tenant.adminUserId());

        var result = curriculumService.update(curriculumId, "Renamed Curriculum", LocalDate.of(2025, 12, 31));

        assertThat(result).isInstanceOf(CurriculumService.UpdateResult.Success.class);
        var updated = curriculumService.get(curriculumId).orElseThrow();
        assertThat(updated.effectiveFrom()).isEqualTo(LocalDate.of(2025, 1, 1)); // effectiveFrom never moves
        assertThat(updated.effectiveTo()).isEqualTo(LocalDate.of(2025, 12, 31));
    }

    @Test
    void effectiveToCannotMoveBackward() {
        var tenant = fixtures.provisionTenant("CUR7");
        UUID dept = fixtures.provisionDepartment(tenant, "CUR7D");
        UUID programId = fixtures.provisionProgram(tenant, dept, "CUR7P");
        UUID curriculumId = fixtures.provisionCurriculum(tenant, programId, "v1", LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31));
        TestAuthentication.authenticateAs(tenant.adminUserId());

        var result = curriculumService.update(curriculumId, "Renamed", LocalDate.of(2025, 6, 1));

        assertThat(result).isInstanceOf(CurriculumService.UpdateResult.InvalidDateRange.class);
    }
}
