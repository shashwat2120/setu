package com.setu.risk.application;

import com.setu.risk.domain.RiskBand;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Day 13 §Student Success Engine: pure-function tests for {@link
 * RiskScoringEngine} — no Spring context, no database, since {@link
 * RiskScoringEngine#calculate} takes no I/O of any kind. Verifies the
 * deterministic reproducibility guarantee directly: same snapshot in,
 * byte-identical result out, every time.
 */
class RiskScoringEngineTests {

    private final RiskScoringEngine engine = new RiskScoringEngine();

    private RiskFeatureService.Feature feature(String code, String value) {
        return new RiskFeatureService.Feature(code, new BigDecimal(value),
            List.of(new RiskFeatureService.EvidenceRef("enrollments", UUID.randomUUID())));
    }

    @Test
    void allSafeValues_produceZeroScoreAndLowBand() {
        var snapshot = new RiskFeatureService.FeatureSnapshot(List.of(
            feature("ATTENDANCE_TREND", "100"),
            feature("ASSESSMENT_PERFORMANCE", "4.0"),
            feature("RECENT_FAILURES", "0"),
            feature("ASSIGNMENT_COMPLETION", "0"),
            feature("LEARNING_PROGRESS", "1.0")
        ), List.of());

        var result = engine.calculate(snapshot);

        assertThat(result.score()).isEqualByComparingTo("0.000");
        assertThat(result.band()).isEqualTo(RiskBand.LOW);
    }

    @Test
    void allMaximallyRiskyValues_produceHighBand() {
        var snapshot = new RiskFeatureService.FeatureSnapshot(List.of(
            feature("ATTENDANCE_TREND", "0"),
            feature("ASSESSMENT_PERFORMANCE", "0"),
            feature("RECENT_FAILURES", "5"),
            feature("ASSIGNMENT_COMPLETION", "1.0"),
            feature("LEARNING_PROGRESS", "0")
        ), List.of());

        var result = engine.calculate(snapshot);

        assertThat(result.score()).isEqualByComparingTo("100.000");
        assertThat(result.band()).isEqualTo(RiskBand.HIGH);
    }

    @Test
    void failingGradeAlone_landsExactlyOnMediumBoundary() {
        // ASSESSMENT_PERFORMANCE (gradePoint 0, weight 0.25 -> 25) + RECENT_FAILURES (1 failure / cap 3, weight 0.15 -> 5) = 30.000
        var snapshot = new RiskFeatureService.FeatureSnapshot(List.of(
            feature("ATTENDANCE_TREND", "100"),
            feature("ASSESSMENT_PERFORMANCE", "0"),
            feature("RECENT_FAILURES", "1"),
            feature("ASSIGNMENT_COMPLETION", "0"),
            feature("LEARNING_PROGRESS", "1.0")
        ), List.of());

        var result = engine.calculate(snapshot);

        assertThat(result.score()).isEqualByComparingTo("30.000");
        assertThat(result.band()).isEqualTo(RiskBand.MEDIUM);
    }

    @Test
    void sameSnapshotCalculatedTwice_producesIdenticalResult() {
        var snapshot = new RiskFeatureService.FeatureSnapshot(List.of(
            feature("ATTENDANCE_TREND", "62.5"),
            feature("ASSESSMENT_PERFORMANCE", "1.75"),
            feature("RECENT_FAILURES", "2"),
            feature("ASSIGNMENT_COMPLETION", "0.4"),
            feature("LEARNING_PROGRESS", "0.6")
        ), List.of());

        var first = engine.calculate(snapshot);
        var second = engine.calculate(snapshot);

        assertThat(first.score()).isEqualByComparingTo(second.score());
        assertThat(first.band()).isEqualTo(second.band());
        assertThat(first.factors()).hasSameSizeAs(second.factors());
        for (int i = 0; i < first.factors().size(); i++) {
            assertThat(first.factors().get(i).factorCode()).isEqualTo(second.factors().get(i).factorCode());
            assertThat(first.factors().get(i).contribution()).isEqualByComparingTo(second.factors().get(i).contribution());
        }
    }

    @Test
    void factorsAreSortedByContributionDescending() {
        var snapshot = new RiskFeatureService.FeatureSnapshot(List.of(
            feature("ATTENDANCE_TREND", "0"),      // full 30
            feature("ASSESSMENT_PERFORMANCE", "4.0"), // 0
            feature("RECENT_FAILURES", "0"),        // 0
            feature("ASSIGNMENT_COMPLETION", "1.0"), // full 15
            feature("LEARNING_PROGRESS", "1.0")     // 0
        ), List.of());

        var result = engine.calculate(snapshot);

        assertThat(result.factors().get(0).factorCode()).isEqualTo("ATTENDANCE_TREND");
        assertThat(result.factors().get(0).contribution()).isGreaterThan(result.factors().get(1).contribution());
    }
}
