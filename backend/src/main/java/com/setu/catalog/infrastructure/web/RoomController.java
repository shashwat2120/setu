package com.setu.catalog.infrastructure.web;

import com.setu.catalog.application.RoomService;
import com.setu.catalog.domain.Room;
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
@RequestMapping("/catalog/rooms")
public class RoomController {

    private final RoomService roomService;

    public RoomController(RoomService roomService) {
        this.roomService = roomService;
    }

    public record CreateRequest(UUID buildingId, String code, int capacity, String featuresJson) {
    }

    public record RoomResponse(UUID id, UUID tenantId, UUID buildingId, String code, int capacity, String featuresJson) {
    }

    private static RoomResponse toResponse(Room r) {
        return new RoomResponse(r.id(), r.tenantId(), r.buildingId(), r.code(), r.capacity(), r.featuresJson());
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody CreateRequest request) {
        var result = roomService.create(request.buildingId(), request.code(), request.capacity(), request.featuresJson());
        return switch (result) {
            case RoomService.CreateResult.Success success -> ResponseEntity.status(HttpStatus.CREATED)
                .body(roomService.get(success.roomId()).map(RoomController::toResponse).orElseThrow());
            case RoomService.CreateResult.BuildingNotFound ignored ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("building not found"));
            case RoomService.CreateResult.CodeAlreadyInUse ignored ->
                ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse("room code already in use for this building"));
            case RoomService.CreateResult.InvalidRoomData invalid ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse(invalid.message()));
        };
    }

    @GetMapping("/{roomId}")
    public ResponseEntity<?> get(@PathVariable UUID roomId) {
        return roomService.get(roomId).map(r -> ResponseEntity.ok(toResponse(r)))
            .orElse(ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }

    @GetMapping
    public ResponseEntity<List<RoomResponse>> listForBuilding(@RequestParam UUID buildingId, @RequestParam UUID tenantId) {
        return ResponseEntity.ok(roomService.listForBuilding(buildingId, tenantId).stream().map(RoomController::toResponse).toList());
    }

    public record UpdateRequest(int capacity, String featuresJson) {
    }

    @PutMapping("/{roomId}")
    public ResponseEntity<?> update(@PathVariable UUID roomId, @RequestBody UpdateRequest request) {
        var result = roomService.update(roomId, request.capacity(), request.featuresJson());
        return switch (result) {
            case RoomService.UpdateResult.Success ignored ->
                ResponseEntity.ok(roomService.get(roomId).map(RoomController::toResponse).orElseThrow());
            case RoomService.UpdateResult.NotFound ignored -> ResponseEntity.status(HttpStatus.NOT_FOUND).build();
            case RoomService.UpdateResult.InvalidRoomData invalid ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse(invalid.message()));
        };
    }

    public record ErrorResponse(String message) {
    }
}
