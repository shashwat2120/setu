package com.setu.risk.infrastructure.web;

import com.setu.risk.application.InterventionService;
import com.setu.risk.domain.Intervention;
import com.setu.risk.domain.InterventionAction;
import com.setu.risk.domain.InterventionStatus;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/risk/interventions")
public class InterventionController {

    private final InterventionService interventionService;

    public InterventionController(InterventionService interventionService) {
        this.interventionService = interventionService;
    }

    public record ActionResponse(UUID id, String action, UUID actorUserId, String notes, Instant createdAt) {
    }

    private static ActionResponse toResponse(InterventionAction a) {
        return new ActionResponse(a.id(), a.action(), a.actorUserId(), a.notes(), a.createdAt());
    }

    public record InterventionResponse(UUID id, UUID tenantId, UUID studentId, UUID riskScoreId, UUID recommendationId,
                                        UUID ownerUserId, String status, LocalDate dueDate, List<ActionResponse> actions) {
    }

    private InterventionResponse toResponse(Intervention i) {
        List<ActionResponse> actions = interventionService.actionsFor(i.id()).stream()
            .map(InterventionController::toResponse).toList();
        return new InterventionResponse(i.id(), i.tenantId(), i.studentId(), i.riskScoreId(), i.recommendationId(),
            i.ownerUserId(), i.status().name(), i.dueDate(), actions);
    }

    @GetMapping
    public ResponseEntity<List<InterventionResponse>> listForStudent(@RequestParam UUID studentId) {
        return ResponseEntity.ok(interventionService.listForStudent(studentId).stream().map(this::toResponse).toList());
    }

    @GetMapping("/{interventionId}")
    public ResponseEntity<InterventionResponse> byId(@PathVariable UUID interventionId) {
        return interventionService.getById(interventionId)
            .map(this::toResponse)
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }

    public record TransitionRequest(InterventionStatus target) {
    }

    private ResponseEntity<?> handleTransition(InterventionService.TransitionResult result) {
        return switch (result) {
            case SUCCESS -> ResponseEntity.noContent().build();
            case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).build();
            case INVALID_TRANSITION -> ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse("invalid status transition"));
            case COMPLETION_REQUIRES_ACTION ->
                ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse("at least one intervention_action is required before completion"));
            case REOPEN_REQUIRES_ADMIN -> ResponseEntity.status(HttpStatus.FORBIDDEN).body(new ErrorResponse("reopening a completed intervention requires tenant-wide manage permission"));
        };
    }

    @PostMapping("/{interventionId}/transition")
    public ResponseEntity<?> transition(@PathVariable UUID interventionId, @RequestBody TransitionRequest request) {
        return handleTransition(interventionService.transition(interventionId, request.target()));
    }

    public record AddActionRequest(String action, String notes) {
    }

    @PostMapping("/{interventionId}/actions")
    public ResponseEntity<?> addAction(@PathVariable UUID interventionId, @RequestBody AddActionRequest request) {
        return switch (interventionService.addAction(interventionId, request.action(), request.notes())) {
            case SUCCESS -> ResponseEntity.status(HttpStatus.CREATED).build();
            case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).build();
            case TERMINAL_STATE -> ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse("cannot record an action on a cancelled intervention"));
        };
    }

    public record ErrorResponse(String message) {
    }
}
