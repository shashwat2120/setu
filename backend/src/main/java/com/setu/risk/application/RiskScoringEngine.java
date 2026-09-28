package com.setu.risk.application;

import com.setu.risk.domain.RiskBand;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Day 13 §Student Success Engine: the deterministic rule engine itself —
 * pure functions only (no I/O, no repository access), so given the same
 * {@link RiskFeatureService.FeatureSnapshot} it always produces the exact
 * same {@link Result}, satisfying the canonical spec's reproducibility
 * requirement together with persisting the raw feature values (see {@code
 * RiskScore}'s javadoc).
 * <p>
 * {@link #RULE_VERSION} must be bumped whenever ANY weight, threshold, or
 * formula below changes — a {@code RiskScore} row's {@code rule_version}
 * is what lets a later reviewer know exactly which version of this logic
 * produced it; old scores are never retroactively reinterpreted under a
 * new rule version (state-machines.md #21's "Correction" note).
 * <p>
 * No specific algorithm is mandated anywhere in the canonical spec
 * (confirmed: docs/PRD.md §8 explicitly leaves "exact risk-scoring
 * formula... weighting" to this engine's own design, not a prior
 * sign-off) — every weight and threshold below is this module's own
 * disclosed choice, not a transcription of a documented formula.
 */
@Component
class RiskScoringEngine {

    static final String RULE_VERSION = "v1";

    private static final BigDecimal ATTENDANCE_WEIGHT = new BigDecimal("0.30");
    private static final BigDecimal ASSESSMENT_WEIGHT = new BigDecimal("0.25");
    private static final BigDecimal RECENT_FAILURES_WEIGHT = new BigDecimal("0.15");
    private static final BigDecimal ASSIGNMENT_COMPLETION_WEIGHT = new BigDecimal("0.15");
    private static final BigDecimal LEARNING_PROGRESS_WEIGHT = new BigDecimal("0.15");

    /** Attendance at/above this percentage contributes zero risk; 0% contributes full weight. Disclosed threshold, not mandated. */
    private static final BigDecimal ATTENDANCE_SAFE_THRESHOLD = new BigDecimal("75");
    /** Average grade point (4.0 scale, matching this codebase's existing gradePoint domain) at/above this contributes zero risk. Disclosed threshold, not mandated. */
    private static final BigDecimal GRADE_POINT_SAFE_THRESHOLD = new BigDecimal("2.5");
    /** Recent failure count at/above this contributes full weight; scales linearly below it. Disclosed threshold, not mandated. */
    private static final BigDecimal FAILURE_COUNT_CAP = new BigDecimal("3");

    private static final BigDecimal HIGH_BAND_THRESHOLD = new BigDecimal("60");
    private static final BigDecimal MEDIUM_BAND_THRESHOLD = new BigDecimal("30");

    record FactorResult(String factorCode, BigDecimal contribution) {
    }

    record Result(BigDecimal score, RiskBand band, List<FactorResult> factors) {
    }

    Result calculate(RiskFeatureService.FeatureSnapshot snapshot) {
        BigDecimal totalScore = BigDecimal.ZERO;
        java.util.List<FactorResult> factors = new java.util.ArrayList<>();

        for (RiskFeatureService.Feature feature : snapshot.features()) {
            BigDecimal contribution = switch (feature.code()) {
                case "ATTENDANCE_TREND" -> weighted(riskFromSafeAbove(feature.rawValue(), ATTENDANCE_SAFE_THRESHOLD, new BigDecimal("100")), ATTENDANCE_WEIGHT);
                case "ASSESSMENT_PERFORMANCE" -> weighted(riskFromSafeAbove(feature.rawValue(), GRADE_POINT_SAFE_THRESHOLD, new BigDecimal("4.0")), ASSESSMENT_WEIGHT);
                case "RECENT_FAILURES" -> weighted(cappedRatio(feature.rawValue(), FAILURE_COUNT_CAP), RECENT_FAILURES_WEIGHT);
                case "ASSIGNMENT_COMPLETION" -> weighted(clamp01(feature.rawValue()), ASSIGNMENT_COMPLETION_WEIGHT);
                case "LEARNING_PROGRESS" -> weighted(BigDecimal.ONE.subtract(clamp01(feature.rawValue())), LEARNING_PROGRESS_WEIGHT);
                default -> BigDecimal.ZERO;
            };
            totalScore = totalScore.add(contribution);
            factors.add(new FactorResult(feature.code(), contribution.setScale(3, RoundingMode.HALF_UP)));
        }

        BigDecimal finalScore = totalScore.setScale(3, RoundingMode.HALF_UP);
        RiskBand band = finalScore.compareTo(HIGH_BAND_THRESHOLD) >= 0 ? RiskBand.HIGH
            : finalScore.compareTo(MEDIUM_BAND_THRESHOLD) >= 0 ? RiskBand.MEDIUM : RiskBand.LOW;
        factors.sort((a, b) -> b.contribution().compareTo(a.contribution()));
        return new Result(finalScore, band, factors);
    }

    private BigDecimal weighted(BigDecimal risk01, BigDecimal weight) {
        return risk01.multiply(weight).multiply(new BigDecimal("100"));
    }

    /** 0 risk at/above {@code safeThreshold}, scaling linearly to 1 at 0. */
    private BigDecimal riskFromSafeAbove(BigDecimal value, BigDecimal safeThreshold, BigDecimal ignoredMax) {
        if (value.compareTo(safeThreshold) >= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal deficit = safeThreshold.subtract(value);
        return clamp01(deficit.divide(safeThreshold, 6, RoundingMode.HALF_UP));
    }

    private BigDecimal cappedRatio(BigDecimal value, BigDecimal cap) {
        return clamp01(value.divide(cap, 6, RoundingMode.HALF_UP));
    }

    private BigDecimal clamp01(BigDecimal value) {
        if (value.compareTo(BigDecimal.ZERO) < 0) {
            return BigDecimal.ZERO;
        }
        if (value.compareTo(BigDecimal.ONE) > 0) {
            return BigDecimal.ONE;
        }
        return value;
    }
}
