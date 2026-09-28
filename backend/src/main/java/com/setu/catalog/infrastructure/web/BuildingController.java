package com.setu.catalog.infrastructure.web;

import com.setu.catalog.application.BuildingService;
import com.setu.catalog.domain.Building;
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
@RequestMapping("/catalog/buildings")
public class BuildingController {

    private final BuildingService buildingService;

    public BuildingController(BuildingService buildingService) {
        this.buildingService = buildingService;
    }

    public record CreateRequest(UUID tenantId, String code, String name) {
    }

    public record BuildingResponse(UUID id, UUID tenantId, String code, String name) {
    }

    private static BuildingResponse toResponse(Building b) {
        return new BuildingResponse(b.id(), b.tenantId(), b.code(), b.name());
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody CreateRequest request) {
        var result = buildingService.create(request.tenantId(), request.code(), request.name());
        return switch (result) {
            case BuildingService.CreateResult.Success success -> ResponseEntity.status(HttpStatus.CREATED)
                .body(buildingService.get(success.buildingId()).map(BuildingController::toResponse).orElseThrow());
            case BuildingService.CreateResult.CodeAlreadyInUse ignored ->
                ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse("building code already in use"));
        };
    }

    @GetMapping("/{buildingId}")
    public ResponseEntity<?> get(@PathVariable UUID buildingId) {
        return buildingService.get(buildingId).map(b -> ResponseEntity.ok(toResponse(b)))
            .orElse(ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }

    @GetMapping
    public ResponseEntity<List<BuildingResponse>> listForTenant(@RequestParam UUID tenantId) {
        return ResponseEntity.ok(buildingService.listForTenant(tenantId).stream().map(BuildingController::toResponse).toList());
    }

    public record UpdateRequest(String name) {
    }

    @PutMapping("/{buildingId}")
    public ResponseEntity<?> update(@PathVariable UUID buildingId, @RequestBody UpdateRequest request) {
        return switch (buildingService.update(buildingId, request.name())) {
            case SUCCESS -> ResponseEntity.ok(buildingService.get(buildingId).map(BuildingController::toResponse).orElseThrow());
            case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        };
    }

    public record ErrorResponse(String message) {
    }
}
