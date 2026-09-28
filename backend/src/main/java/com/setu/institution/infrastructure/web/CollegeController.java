package com.setu.institution.infrastructure.web;

import com.setu.institution.application.CollegeService;
import com.setu.institution.domain.College;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * All authorization happens inside CollegeService via the Day 4 engine —
 * this controller never checks a role itself (Day 5 §9: "do not write
 * if(role == ADMIN) inside controllers").
 */
@RestController
@RequestMapping("/institution/colleges")
public class CollegeController {

    private final CollegeService collegeService;

    public CollegeController(CollegeService collegeService) {
        this.collegeService = collegeService;
    }

    public record CreateRequest(String code, String name, String timezone) {
    }

    public record CollegeResponse(UUID id, String code, String name, String timezone, String locale, String status) {
    }

    private static CollegeResponse toResponse(College c) {
        return new CollegeResponse(c.id(), c.code(), c.name(), c.timezone(), c.locale(), c.status().name());
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody CreateRequest request) {
        return switch (collegeService.create(request.code(), request.name(), request.timezone())) {
            case CollegeService.CreateResult.Success success -> ResponseEntity.status(HttpStatus.CREATED)
                .body(collegeService.get(success.collegeId()).map(CollegeController::toResponse).orElseThrow());
            case CollegeService.CreateResult.CodeAlreadyInUse ignored ->
                ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse("college code already in use"));
        };
    }

    @GetMapping
    public ResponseEntity<List<CollegeResponse>> listAll() {
        return ResponseEntity.ok(collegeService.listAll().stream().map(CollegeController::toResponse).toList());
    }

    @GetMapping("/{collegeId}")
    public ResponseEntity<?> get(@PathVariable UUID collegeId) {
        return collegeService.get(collegeId)
            .map(c -> ResponseEntity.ok(toResponse(c)))
            .orElse(ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }

    public record UpdateRequest(String name, String timezone, String locale) {
    }

    @PutMapping("/{collegeId}")
    public ResponseEntity<?> update(@PathVariable UUID collegeId, @RequestBody UpdateRequest request) {
        var result = collegeService.update(collegeId, request.name(), request.timezone(), request.locale());
        return switch (result) {
            case SUCCESS -> ResponseEntity.ok(collegeService.get(collegeId).map(CollegeController::toResponse).orElseThrow());
            case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        };
    }

    public record StatusChangeRequest(College.CollegeStatus status, String reason) {
    }

    @PostMapping("/{collegeId}/status")
    public ResponseEntity<?> changeStatus(@PathVariable UUID collegeId, @RequestBody StatusChangeRequest request) {
        var result = collegeService.changeStatus(collegeId, request.status(), request.reason());
        return switch (result) {
            case CollegeService.StatusChangeResult.Success ignored -> ResponseEntity.noContent().build();
            case CollegeService.StatusChangeResult.NotFound ignored -> ResponseEntity.status(HttpStatus.NOT_FOUND).build();
            case CollegeService.StatusChangeResult.InvalidTransition ignored ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("invalid status transition"));
            case CollegeService.StatusChangeResult.BlockedByActiveAcademicPeriod ignored -> ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("cannot archive a college with an academic period still mid-term"));
        };
    }

    public record ErrorResponse(String message) {
    }
}
