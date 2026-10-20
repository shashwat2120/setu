package com.setu.career.application;

import com.setu.career.domain.JobTerms;
import com.setu.shared.career.StudentStandings.Standing;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The deadline and academic rules of a job, as pure arithmetic: each way a rule can be met or missed, and what the student is told. */
class EligibilityRulesTests {

    private static final Instant NOW = Instant.parse("2026-10-21T08:00:00Z");
    private static final UUID CS = UUID.randomUUID();
    private static final UUID ARTS = UUID.randomUUID();

    private static JobTerms terms(String minCgpa, String minAttendance, Instant deadline, List<UUID> programs) {
        return new JobTerms(minCgpa == null ? null : new BigDecimal(minCgpa), minAttendance == null ? null : new BigDecimal(minAttendance), deadline,
            null, null, null, null, programs);
    }

    private static Standing standing(String cgpa, Double attendance, UUID program) {
        return new Standing(cgpa == null ? null : new BigDecimal(cgpa), attendance, program);
    }

    private static List<String> codes(JobTerms t, Standing s) {
        return EligibilityRules.unmet(t, NOW, s).stream().map(EligibilityRules.Unmet::code).toList();
    }

    @Test
    void aJobWithNoRulesIsOpenToEveryone() {
        assertThat(EligibilityRules.unmet(JobTerms.NONE, NOW, null)).isEmpty();
        assertThat(EligibilityRules.unmet(JobTerms.NONE, NOW, standing(null, null, null))).isEmpty();
    }

    @Test
    void aCgpaAtOrAboveTheMinimumMeetsItAndBelowDoesNot() {
        var t = terms("7.0", null, null, null);

        assertThat(codes(t, standing("7.00", 50.0, CS))).isEmpty();
        assertThat(codes(t, standing("8.50", 50.0, CS))).isEmpty();
        assertThat(codes(t, standing("6.99", 50.0, CS))).containsExactly(EligibilityRules.MIN_CGPA);
        assertThat(EligibilityRules.unmet(t, NOW, standing("6.40", null, CS)).get(0).message()).isEqualTo("Needs a CGPA of at least 7; yours is 6.4.");
    }

    @Test
    void noPublishedGradesDoesNotMeetACgpaRuleAndSaysWhy() {
        var t = terms("7.0", null, null, null);

        var unmet = EligibilityRules.unmet(t, NOW, standing(null, 90.0, CS));

        assertThat(unmet).singleElement().satisfies(u -> {
            assertThat(u.code()).isEqualTo(EligibilityRules.MIN_CGPA);
            assertThat(u.message()).contains("no published grades yet");
        });
    }

    @Test
    void attendanceAtOrAboveTheMinimumMeetsItAndBelowDoesNot() {
        var t = terms(null, "75", null, null);

        assertThat(codes(t, standing(null, 75.0, CS))).isEmpty();
        assertThat(codes(t, standing(null, 74.9, CS))).containsExactly(EligibilityRules.MIN_ATTENDANCE);
        assertThat(EligibilityRules.unmet(t, NOW, standing(null, 33.3, CS)).get(0).message()).isEqualTo("Needs attendance of at least 75%; yours is 33.3%.");
    }

    @Test
    void noSessionHeldYetDoesNotMeetAnAttendanceRuleAndIsNeverTreatedAsOneHundred() {
        var unmet = EligibilityRules.unmet(terms(null, "75", null, null), NOW, standing("8.0", null, CS));

        assertThat(unmet).singleElement().satisfies(u -> {
            assertThat(u.code()).isEqualTo(EligibilityRules.MIN_ATTENDANCE);
            assertThat(u.message()).contains("no class session has been held");
        });
    }

    @Test
    void onlyStudentsOfTheListedProgramsMeetAProgramRule() {
        var t = terms(null, null, null, List.of(CS));

        assertThat(codes(t, standing(null, null, CS))).isEmpty();
        assertThat(codes(t, standing(null, null, ARTS))).containsExactly(EligibilityRules.PROGRAM_NOT_ALLOWED);
        assertThat(codes(t, standing(null, null, null))).as("a student with no active program").containsExactly(EligibilityRules.PROGRAM_NOT_ALLOWED);
        assertThat(codes(terms(null, null, null, List.of(CS, ARTS)), standing(null, null, ARTS))).isEmpty();
    }

    @Test
    void theDeadlineClosesTheJobAtTheInstantItIsReached() {
        assertThat(codes(terms(null, null, NOW.plusSeconds(1), null), null)).isEmpty();
        assertThat(codes(terms(null, null, NOW, null), null)).containsExactly(EligibilityRules.DEADLINE_PASSED);
        assertThat(codes(terms(null, null, NOW.minusSeconds(3600), null), null)).containsExactly(EligibilityRules.DEADLINE_PASSED);
        assertThat(EligibilityRules.unmet(terms(null, null, Instant.parse("2026-10-21T06:30:00Z"), null), NOW, null).get(0).message())
            .isEqualTo("The application deadline passed on 21 Oct 2026, 12:00 PM (IST).");
    }

    @Test
    void everyRuleThatIsMissedIsReportedNotJustTheFirst() {
        var t = terms("7.5", "80", NOW.minusSeconds(60), List.of(CS));

        assertThat(codes(t, standing("6.0", 40.0, ARTS))).containsExactly(
            EligibilityRules.DEADLINE_PASSED, EligibilityRules.MIN_CGPA, EligibilityRules.MIN_ATTENDANCE, EligibilityRules.PROGRAM_NOT_ALLOWED);
    }

    @Test
    void aStudentWhoseStandingCannotBeLearnedMeetsNoRuleThatNeedsIt() {
        var t = terms("7.0", "75", null, List.of(CS));

        assertThat(codes(t, null)).containsExactly(EligibilityRules.MIN_CGPA, EligibilityRules.MIN_ATTENDANCE, EligibilityRules.PROGRAM_NOT_ALLOWED);
    }

    @Test
    void termsThatMakeNoSenseAreRefusedWithTheReason() {
        assertThatThrownBy(() -> terms("-1", null, null, null).validate()).hasMessageContaining("CGPA");
        assertThatThrownBy(() -> terms("11", null, null, null).validate()).hasMessageContaining("CGPA");
        assertThatThrownBy(() -> terms(null, "101", null, null).validate()).hasMessageContaining("attendance");
        assertThatThrownBy(() -> terms(null, "-5", null, null).validate()).hasMessageContaining("attendance");
        assertThatThrownBy(() -> new JobTerms(null, null, null, new BigDecimal("-1"), null, null, null, null).validate()).hasMessageContaining("package");
        assertThatThrownBy(() -> new JobTerms(null, null, null, null, null, 0, null, null).validate()).hasMessageContaining("opening");
        terms("0", "0", null, null).validate();
        terms("10", "100", null, null).validate();
    }

    @Test
    void emptyOrRepeatedProgramListsAreTidied() {
        assertThat(new JobTerms(null, null, null, null, "  ", null, null, List.of()).allowedProgramIds()).isNull();
        assertThat(new JobTerms(null, null, null, null, "  ", null, null, List.of()).location()).isNull();
        assertThat(new JobTerms(null, null, null, null, " Pune ", null, null, List.of(CS, CS, ARTS)).allowedProgramIds()).containsExactly(CS, ARTS);
        assertThat(new JobTerms(null, null, null, null, " Pune ", null, null, null).location()).isEqualTo("Pune");
    }
}
