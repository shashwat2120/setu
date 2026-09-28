package com.setu.risk.infrastructure.web;

import com.setu.risk.application.RecommendationService;
import com.setu.risk.domain.Recommendation;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/risk/recommendations")
public class RecommendationController {

    private final RecommendationService recommendationService;

    public RecommendationController(RecommendationService recommendationService) {
        this.recommendationService = recommendationService;
    }

    public record RecommendationResponse(UUID id, UUID riskScoreId, String actionCode, String generatedBy, String status) {
    }

    private static RecommendationResponse toResponse(Recommendation r) {
        return new RecommendationResponse(r.id(), r.riskScoreId(), r.actionCode(), r.generatedBy().name(), r.status().name());
    }

    @GetMapping
    public ResponseEntity<List<RecommendationResponse>> listForRiskScore(@RequestParam UUID riskScoreId) {
        return ResponseEntity.ok(recommendationService.listForRiskScore(riskScoreId).stream().map(RecommendationController::toResponse).toList());
    }

    public record AcceptRequest(LocalDate dueDate) {
    }

    public record AcceptResponse(UUID interventionId) {
    }

    @PostMapping("/{recommendationId}/accept")
    public ResponseEntity<?> accept(@PathVariable UUID recommendationId, @RequestBody AcceptRequest request) {
        var result = recommendationService.accept(recommendationId, request.dueDate());
        return switch (result) {
            case RecommendationService.DecisionResult.Success r -> ResponseEntity.ok(new AcceptResponse(r.interventionId()));
            case RecommendationService.DecisionResult.NotFound ignored -> ResponseEntity.status(HttpStatus.NOT_FOUND).build();
            case RecommendationService.DecisionResult.InvalidTransition ignored -> ResponseEntity.status(HttpStatus.CONFLICT).build();
        };
    }

    @PostMapping("/{recommendationId}/dismiss")
    public ResponseEntity<?> dismiss(@PathVariable UUID recommendationId) {
        return switch (recommendationService.dismiss(recommendationId)) {
            case SUCCESS -> ResponseEntity.noContent().build();
            case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).build();
            case INVALID_TRANSITION -> ResponseEntity.status(HttpStatus.CONFLICT).build();
        };
    }
}
