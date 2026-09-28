package com.setu.institution.infrastructure.web;

import com.setu.institution.application.CampusService;
import com.setu.institution.domain.Campus;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/institution/campuses")
public class CampusController {

    private final CampusService campusService;

    public CampusController(CampusService campusService) {
        this.campusService = campusService;
    }

    public record CreateRequest(UUID collegeId, String code, String name) {
    }

    public record CampusResponse(UUID id, UUID tenantId, String code, String name) {
    }

    private static CampusResponse toResponse(Campus c) {
        return new CampusResponse(c.id(), c.tenantId(), c.code(), c.name());
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody CreateRequest request) {
        return switch (campusService.create(request.collegeId(), request.code(), request.name())) {
            case CampusService.CreateResult.Success success -> ResponseEntity.status(HttpStatus.CREATED)
                .body(campusService.get(success.campusId()).map(CampusController::toResponse).orElseThrow());
            case CampusService.CreateResult.CollegeNotFound ignored ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("college not found"));
            case CampusService.CreateResult.CodeAlreadyInUse ignored ->
                ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse("campus code already in use for this college"));
        };
    }

    @GetMapping("/{campusId}")
    public ResponseEntity<?> get(@PathVariable UUID campusId) {
        return campusService.get(campusId)
            .map(c -> ResponseEntity.ok(toResponse(c)))
            .orElse(ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }

    @GetMapping
    public ResponseEntity<List<CampusResponse>> listForCollege(@RequestParam UUID collegeId) {
        return ResponseEntity.ok(campusService.listForCollege(collegeId).stream().map(CampusController::toResponse).toList());
    }

    public record UpdateRequest(String name) {
    }

    @PutMapping("/{campusId}")
    public ResponseEntity<?> update(@PathVariable UUID campusId, @RequestBody UpdateRequest request) {
        return switch (campusService.update(campusId, request.name())) {
            case SUCCESS -> ResponseEntity.ok(campusService.get(campusId).map(CampusController::toResponse).orElseThrow());
            case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        };
    }

    public record ErrorResponse(String message) {
    }
}
