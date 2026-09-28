package com.setu.assessment.infrastructure.web;

import com.setu.assessment.application.AssessmentComponentService;
import com.setu.assessment.domain.AssessmentComponent;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Day 11 §Components: assessment_components — see AssessmentComponentService's javadoc. */
@RestController
@RequestMapping("/assessment/components")
public class AssessmentComponentController {

    private final AssessmentComponentService assessmentComponentService;

    public AssessmentComponentController(AssessmentComponentService assessmentComponentService) {
        this.assessmentComponentService = assessmentComponentService;
    }

    public record CreateRequest(UUID assessmentId, String code, BigDecimal maxMarks, BigDecimal weight, Integer orderNo) {
    }

    public record ComponentResponse(UUID id, UUID assessmentId, String code, BigDecimal maxMarks, BigDecimal weight, Integer orderNo) {
    }

    private static ComponentResponse toResponse(AssessmentComponent c) {
        return new ComponentResponse(c.id(), c.assessmentId(), c.code(), c.maxMarks(), c.weight(), c.orderNo());
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody CreateRequest request) {
        var result = assessmentComponentService.create(request.assessmentId(), request.code(), request.maxMarks(),
            request.weight(), request.orderNo());
        return switch (result) {
            case AssessmentComponentService.CreateResult.Success r -> ResponseEntity.status(HttpStatus.CREATED)
                .body(toResponse(assessmentComponentService.get(r.componentId()).orElseThrow()));
            case AssessmentComponentService.CreateResult.AssessmentNotFound ignored ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("assessment not found"));
            case AssessmentComponentService.CreateResult.NotDraft ignored ->
                ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse("assessment is no longer in DRAFT"));
            case AssessmentComponentService.CreateResult.InvalidComponentData invalid ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse(invalid.message()));
            case AssessmentComponentService.CreateResult.CodeAlreadyInUse ignored ->
                ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse("a component already uses this code in this assessment"));
        };
    }

    @GetMapping
    public ResponseEntity<List<ComponentResponse>> listForAssessment(@RequestParam UUID assessmentId) {
        return ResponseEntity.ok(assessmentComponentService.listForAssessment(assessmentId).stream()
            .map(AssessmentComponentController::toResponse).toList());
    }

    @DeleteMapping("/{componentId}")
    public ResponseEntity<?> delete(@PathVariable UUID componentId) {
        var result = assessmentComponentService.delete(componentId);
        return switch (result) {
            case SUCCESS -> ResponseEntity.noContent().build();
            case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).build();
            case NOT_DRAFT -> ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse("assessment is no longer in DRAFT"));
        };
    }

    public record ErrorResponse(String message) {
    }
}
