package com.setu.catalog.application;

import com.setu.identity.TestAuthentication;
import com.setu.identity.domain.RoleCode;
import com.setu.identity.domain.UserRole;
import com.setu.identity.infrastructure.RoleRepository;
import com.setu.identity.infrastructure.UserRoleRepository;
import com.setu.identity.application.UserRegistrationService;
import com.setu.institution.application.CollegeDirectoryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Day 5's required test matrix items 8, 9, 10 — academic calendar domain validation, not left to the UI. */
@SpringBootTest
@Transactional
class AcademicCalendarValidationTests {

    @Autowired
    AcademicYearService academicYearService;
    @Autowired
    AcademicPeriodService academicPeriodService;
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

    private UUID newTenant() {
        return collegeDirectoryService.provisionCollege("Y" + UUID.randomUUID().toString().substring(0, 8),
            "Calendar Test College", "Asia/Kolkata");
    }

    private UUID newCollegeAdmin(UUID tenantId) {
        var outcome = (UserRegistrationService.Outcome.Created)
            userRegistrationService.register("cal-admin-" + UUID.randomUUID() + "@example.com");
        UUID roleId = roleRepository.findByCode(RoleCode.COLLEGE_ADMIN).orElseThrow().id();
        userRoleRepository.save(new UserRole(UUID.randomUUID(), outcome.userId(), roleId, tenantId, Instant.now(), null));
        return outcome.userId();
    }

    // --- 8. Academic year invalid dates rejected ---
    @Test
    void academicYearWithEndBeforeStartIsRejected() {
        UUID tenant = newTenant();
        UUID admin = newCollegeAdmin(tenant);
        TestAuthentication.authenticateAs(admin);

        assertThatThrownBy(() -> academicYearService.createYear(
            tenant, "AY2026", LocalDate.of(2026, 6, 1), LocalDate.of(2026, 1, 1)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void academicYearWithEqualStartAndEndIsRejected() {
        UUID tenant = newTenant();
        UUID admin = newCollegeAdmin(tenant);
        TestAuthentication.authenticateAs(admin);
        LocalDate sameDay = LocalDate.of(2026, 6, 1);

        assertThatThrownBy(() -> academicYearService.createYear(tenant, "AY2026", sameDay, sameDay))
            .isInstanceOf(IllegalArgumentException.class);
    }

    // --- 9. Academic period outside its academic year rejected ---
    @Test
    void academicPeriodOutsideItsAcademicYearIsRejected() {
        UUID tenant = newTenant();
        UUID admin = newCollegeAdmin(tenant);
        TestAuthentication.authenticateAs(admin);
        UUID yearId = academicYearService.createYear(tenant, "AY2026",
            LocalDate.of(2026, 6, 1), LocalDate.of(2027, 5, 31));

        // Period starts BEFORE the academic year even begins.
        var result = academicPeriodService.createPeriod(yearId, "SEM1", "Semester 1", 1,
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 8, 1), null, null);

        assertThat(result).isInstanceOf(AcademicPeriodService.CreateResult.OutsideAcademicYearBounds.class);
    }

    @Test
    void academicPeriodWithinItsAcademicYearIsAccepted() {
        UUID tenant = newTenant();
        UUID admin = newCollegeAdmin(tenant);
        TestAuthentication.authenticateAs(admin);
        UUID yearId = academicYearService.createYear(tenant, "AY2026",
            LocalDate.of(2026, 6, 1), LocalDate.of(2027, 5, 31));

        var result = academicPeriodService.createPeriod(yearId, "TRI1", "Trimester 1", 1,
            LocalDate.of(2026, 6, 1), LocalDate.of(2026, 9, 30), null, null);

        assertThat(result).isInstanceOf(AcademicPeriodService.CreateResult.Success.class);
    }

    // --- 10. Invalid registration window rejected ---
    @Test
    void registrationWindowWithCloseBeforeOpenIsRejectedAtCreation() {
        UUID tenant = newTenant();
        UUID admin = newCollegeAdmin(tenant);
        TestAuthentication.authenticateAs(admin);
        UUID yearId = academicYearService.createYear(tenant, "AY2026",
            LocalDate.of(2026, 6, 1), LocalDate.of(2027, 5, 31));
        Instant open = Instant.parse("2026-05-01T00:00:00Z");
        Instant closeBeforeOpen = Instant.parse("2026-04-01T00:00:00Z");

        var result = academicPeriodService.createPeriod(yearId, "SEM1", "Semester 1", 1,
            LocalDate.of(2026, 6, 1), LocalDate.of(2026, 12, 1), open, closeBeforeOpen);

        assertThat(result).isInstanceOf(AcademicPeriodService.CreateResult.InvalidPeriodData.class);
    }

    @Test
    void registrationWindowWithCloseBeforeOpenIsRejectedOnUpdate() {
        UUID tenant = newTenant();
        UUID admin = newCollegeAdmin(tenant);
        TestAuthentication.authenticateAs(admin);
        UUID yearId = academicYearService.createYear(tenant, "AY2026",
            LocalDate.of(2026, 6, 1), LocalDate.of(2027, 5, 31));
        var period = (AcademicPeriodService.CreateResult.Success) academicPeriodService.createPeriod(
            yearId, "SEM1", "Semester 1", 1, LocalDate.of(2026, 6, 1), LocalDate.of(2026, 12, 1), null, null);

        Instant open = Instant.parse("2026-05-01T00:00:00Z");
        Instant closeBeforeOpen = Instant.parse("2026-04-01T00:00:00Z");
        var result = academicPeriodService.updateRegistrationWindow(period.periodId(), open, closeBeforeOpen);

        assertThat(result).isEqualTo(AcademicPeriodService.RegistrationWindowResult.INVALID_WINDOW);
    }

    // --- Day 16 §college-admin: listForYear ---
    @Test
    void listForYear_returnsOnlyPeriodsForThatYearAndTenant() {
        UUID tenant = newTenant();
        UUID admin = newCollegeAdmin(tenant);
        TestAuthentication.authenticateAs(admin);
        UUID yearId = academicYearService.createYear(tenant, "LY2026",
            LocalDate.of(2026, 6, 1), LocalDate.of(2027, 5, 31));
        UUID otherYearId = academicYearService.createYear(tenant, "LY2027",
            LocalDate.of(2027, 6, 1), LocalDate.of(2028, 5, 31));
        academicPeriodService.createPeriod(yearId, "SEM1", "Semester 1", 1,
            LocalDate.of(2026, 6, 1), LocalDate.of(2026, 12, 1), null, null);
        academicPeriodService.createPeriod(yearId, "SEM2", "Semester 2", 2,
            LocalDate.of(2027, 1, 1), LocalDate.of(2027, 5, 31), null, null);
        academicPeriodService.createPeriod(otherYearId, "SEM1", "Semester 1", 1,
            LocalDate.of(2027, 6, 1), LocalDate.of(2027, 12, 1), null, null);

        var periods = academicPeriodService.listForYear(yearId);

        assertThat(periods).hasSize(2);
        assertThat(periods).extracting(p -> p.code()).containsExactlyInAnyOrder("SEM1", "SEM2");
        assertThat(periods).extracting(p -> p.academicYearId()).containsOnly(yearId);
    }

    @Test
    void academicYearLifecycle_currentSucceedsThenClosesOnlyAfterPeriodsClose() {
        UUID tenant = newTenant();
        UUID admin = newCollegeAdmin(tenant);
        TestAuthentication.authenticateAs(admin);
        UUID yearId = academicYearService.createYear(tenant, "AY2026",
            LocalDate.of(2026, 6, 1), LocalDate.of(2027, 5, 31));
        @SuppressWarnings("unused") // the cast asserts the period was created
        var period = (AcademicPeriodService.CreateResult.Success) academicPeriodService.createPeriod(
            yearId, "SEM1", "Semester 1", 1, LocalDate.of(2026, 6, 1), LocalDate.of(2026, 12, 1), null, null);

        assertThat(academicYearService.markCurrent(yearId)).isEqualTo(AcademicYearService.MarkCurrentResult.SUCCESS);

        // Cannot close the year while its period is still PLANNED (not CLOSED).
        assertThat(academicYearService.close(yearId)).isEqualTo(AcademicYearService.CloseResult.OPEN_PERIODS_REMAIN);
    }
}
