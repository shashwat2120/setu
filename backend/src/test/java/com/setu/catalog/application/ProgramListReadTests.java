package com.setu.catalog.application;

import com.setu.identity.TestAuthentication;
import com.setu.identity.domain.RoleCode;
import com.setu.testsupport.Day12TestFixtures;
import com.setu.testsupport.Day7TestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A placement officer picks which programs a job is open to, so they can read the program list (and only that, through the narrow VIEW_PROGRAMS), in their
 * own college only; a student, who has no business with it, still cannot.
 */
@SpringBootTest
class ProgramListReadTests {

    @Autowired
    Day7TestFixtures fixtures;
    @Autowired
    Day12TestFixtures day12Fixtures;
    @Autowired
    ProgramService programService;

    @AfterEach
    void clear() {
        TestAuthentication.clear();
    }

    @Test
    void aPlacementOfficerReadsTheirOwnCollegesPrograms() {
        var tenant = fixtures.provisionTenant("PLR1");
        UUID dept = fixtures.provisionDepartment(tenant, "PLR1D");
        UUID programId = fixtures.provisionProgram(tenant, dept, "PLR1P");
        TestAuthentication.authenticateAs(day12Fixtures.provisionPlacementOfficer(tenant, "PLR1O"));

        assertThat(programService.listForTenant(tenant.tenantId())).extracting(p -> p.id()).contains(programId);
    }

    @Test
    void anOfficerOfAnotherCollegeStillCannotReadThem() {
        var tenant = fixtures.provisionTenant("PLR2A");
        var other = fixtures.provisionTenant("PLR2B");
        TestAuthentication.authenticateAs(day12Fixtures.provisionPlacementOfficer(other, "PLR2O"));

        assertThatThrownBy(() -> programService.listForTenant(tenant.tenantId())).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void aStudentCannotReadTheList() {
        var tenant = fixtures.provisionTenant("PLR3");
        TestAuthentication.authenticateAs(fixtures.provisionUserWithRole(tenant.tenantId(), RoleCode.STUDENT, "PLR3S"));

        assertThatThrownBy(() -> programService.listForTenant(tenant.tenantId())).isInstanceOf(AccessDeniedException.class);
    }
}
