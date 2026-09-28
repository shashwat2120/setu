package com.setu.risk.infrastructure.web;

import com.setu.risk.application.RiskCalculationService;
import com.setu.risk.domain.RiskScore;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/risk/scores")
public class RiskScoreController {

    private final RiskCalculationService riskCalculationService;

    public RiskScoreController(RiskCalculationService riskCalculationService) {
        this.riskCalculationService = riskCalculationService;
    }

    public record FactorResponse(UUID id, String factorCode, BigDecimal contribution, String evidenceRefs) {
    }

    public record RiskScoreResponse(UUID id, UUID tenantId, UUID studentId, BigDecimal score, String band,
                                     String ruleVersion, String featureSnapshotHash, String featureSnapshot,
                                     Instant calculatedAt, List<FactorResponse> factors) {
    }

    private RiskScoreResponse toResponse(RiskScore riskScore) {
        List<FactorResponse> factors = riskCalculationService.factorsFor(riskScore.id()).stream()
            .map(f -> new FactorResponse(f.id(), f.factorCode(), f.contribution(), f.evidenceRefs())).toList();
        return new RiskScoreResponse(riskScore.id(), riskScore.tenantId(), riskScore.studentId(), riskScore.score(),
            riskScore.band().name(), riskScore.ruleVersion(), riskScore.featureSnapshotHash(), riskScore.featureSnapshot(),
            riskScore.calculatedAt(), factors);
    }

    public record RecalculateResponse(UUID riskScoreId, String band, boolean bandChanged) {
    }

    @PostMapping("/recalculate")
    public ResponseEntity<?> recalculate(@RequestParam UUID studentId) {
        var result = riskCalculationService.recalculate(studentId);
        return switch (result) {
            case RiskCalculationService.RecalculateResult.Success r ->
                ResponseEntity.ok(new RecalculateResponse(r.riskScoreId(), r.band().name(), r.bandChanged()));
            case RiskCalculationService.RecalculateResult.StudentNotFound ignored ->
                ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        };
    }

    @GetMapping
    public ResponseEntity<List<RiskScoreResponse>> listForTenant(@RequestParam UUID tenantId) {
        return ResponseEntity.ok(riskCalculationService.listCurrentForTenant(tenantId).stream().map(this::toResponse).toList());
    }

    @GetMapping("/current")
    public ResponseEntity<RiskScoreResponse> current(@RequestParam UUID studentId) {
        return riskCalculationService.getCurrent(studentId)
            .map(this::toResponse)
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }

    @GetMapping("/history")
    public ResponseEntity<List<RiskScoreResponse>> history(@RequestParam UUID studentId) {
        return ResponseEntity.ok(riskCalculationService.history(studentId).stream().map(this::toResponse).toList());
    }

    @GetMapping("/{riskScoreId}")
    public ResponseEntity<RiskScoreResponse> byId(@PathVariable UUID riskScoreId) {
        return riskCalculationService.getById(riskScoreId)
            .map(this::toResponse)
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }
}
