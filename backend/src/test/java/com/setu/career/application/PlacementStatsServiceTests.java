package com.setu.career.application;

import com.setu.identity.TestAuthentication;
import com.setu.testsupport.Day12TestFixtures;
import com.setu.testsupport.Day7TestFixtures;
import com.setu.testsupport.Day8TestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The statistics for a real college: read only by its own placement officer or administrator, and counting what that college actually has. */
@SpringBootTest
class PlacementStatsServiceTests {

    @Autowired
    Day7TestFixtures fixtures;
    @Autowired
    Day8TestFixtures day8Fixtures;
    @Autowired
    Day12TestFixtures day12Fixtures;
    @Autowired
    JobService jobService;
    @Autowired
    PlacementRecordService placementRecordService;
    @Autowired
    PlacementStatsService statsService;

    @AfterEach
    void clear() {
        TestAuthentication.clear();
    }

    @Test
    void anOfficerSeesTheirOwnCollegesFigures() {
        var tenant = fixtures.provisionTenant("PS1");
        UUID dept = fixtures.provisionDepartment(tenant, "PS1D");
        UUID programId = fixtures.provisionProgram(tenant, dept, "PS1P");
        UUID curriculumId = fixtures.provisionCurriculum(tenant, programId, "v1", LocalDate.of(2025, 1, 1), null);
        var placed = day8Fixtures.provisionStudentWithActiveProgram(tenant, programId, curriculumId, "PS1A");
        day8Fixtures.provisionStudentWithActiveProgram(tenant, programId, curriculumId, "PS1B");
        UUID officer = day12Fixtures.provisionPlacementOfficer(tenant, "PS1PO");
        TestAuthentication.authenticateAs(officer);
        placementRecordService.record(placed.studentId(), null, "Example Co", "Analyst", new BigDecimal("750000"), LocalDate.now());
        jobService.create(tenant.tenantId(), "Analyst", "Example Co", "d");

        var stats = statsService.forTenant(tenant.tenantId());

        assertThat(stats.placements().activeStudents()).isEqualTo(2);
        assertThat(stats.placements().placedStudents()).isEqualTo(1);
        assertThat(stats.placements().placedPercentage()).isEqualTo(50.0);
        assertThat(stats.placements().byProgram()).singleElement().satisfies(row -> {
            assertThat(row.programId()).isEqualTo(programId);
            assertThat(row.students()).isEqualTo(2);
            assertThat(row.placed()).isEqualTo(1);
        });
        assertThat(stats.placements().packages().averageLpa()).isEqualByComparingTo("7.5");
        assertThat(stats.placements().topRecruiters()).singleElement().satisfies(r -> assertThat(r.employer()).isEqualTo("Example Co"));
        assertThat(stats.jobs().total()).isEqualTo(1);
        assertThat(stats.jobs().open()).isEqualTo(1);
    }

    @Test
    void aStudentAndAnotherCollegesOfficerAreRefused() {
        var tenant = fixtures.provisionTenant("PS2");
        UUID dept = fixtures.provisionDepartment(tenant, "PS2D");
        UUID programId = fixtures.provisionProgram(tenant, dept, "PS2P");
        UUID curriculumId = fixtures.provisionCurriculum(tenant, programId, "v1", LocalDate.of(2025, 1, 1), null);
        var student = day8Fixtures.provisionStudentWithActiveProgram(tenant, programId, curriculumId, "PS2S");
        var other = fixtures.provisionTenant("PS2B");
        UUID otherOfficer = day12Fixtures.provisionPlacementOfficer(other, "PS2BPO");

        TestAuthentication.authenticateAs(student.userId());
        assertThatThrownBy(() -> statsService.forTenant(tenant.tenantId())).isInstanceOf(AccessDeniedException.class);
        TestAuthentication.clear();
        TestAuthentication.authenticateAs(otherOfficer);
        assertThatThrownBy(() -> statsService.forTenant(tenant.tenantId())).isInstanceOf(AccessDeniedException.class);
    }
}
