package com.setu.catalog.infrastructure.web;

import com.setu.catalog.application.MeetingPatternService;
import com.setu.catalog.domain.MeetingPattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/catalog/meeting-patterns")
public class MeetingPatternController {

    private final MeetingPatternService meetingPatternService;

    public MeetingPatternController(MeetingPatternService meetingPatternService) {
        this.meetingPatternService = meetingPatternService;
    }

    public record CreateRequest(UUID sectionId, short dayOfWeek, LocalTime startTime, LocalTime endTime, UUID roomId,
                                 LocalDate effectiveFrom, LocalDate effectiveTo) {
    }

    public record MeetingPatternResponse(UUID id, UUID tenantId, UUID sectionId, short dayOfWeek, LocalTime startTime,
                                          LocalTime endTime, UUID roomId, LocalDate effectiveFrom, LocalDate effectiveTo) {
    }

    private static MeetingPatternResponse toResponse(MeetingPattern p) {
        return new MeetingPatternResponse(p.id(), p.tenantId(), p.sectionId(), p.dayOfWeek(), p.startTime(),
            p.endTime(), p.roomId(), p.effectiveFrom(), p.effectiveTo());
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody CreateRequest request) {
        var result = meetingPatternService.create(request.sectionId(), request.dayOfWeek(), request.startTime(),
            request.endTime(), request.roomId(), request.effectiveFrom(), request.effectiveTo());
        return switch (result) {
            case MeetingPatternService.CreateResult.Success success -> ResponseEntity.status(HttpStatus.CREATED)
                .body(meetingPatternService.listForSection(request.sectionId()).stream()
                    .filter(p -> p.id().equals(success.meetingPatternId())).findFirst().map(MeetingPatternController::toResponse).orElseThrow());
            case MeetingPatternService.CreateResult.SectionNotFound ignored ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("section not found"));
            case MeetingPatternService.CreateResult.RoomNotFound ignored ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("room not found"));
            case MeetingPatternService.CreateResult.TenantMismatch ignored ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("room belongs to a different tenant"));
            case MeetingPatternService.CreateResult.InvalidPatternData invalid ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse(invalid.message()));
            case MeetingPatternService.CreateResult.RoomOverlap ignored ->
                ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse("this room is already booked for an overlapping time"));
        };
    }

    @GetMapping
    public ResponseEntity<List<MeetingPatternResponse>> list(@RequestParam(required = false) UUID sectionId,
                                                               @RequestParam(required = false) UUID roomId) {
        List<MeetingPattern> patterns = sectionId != null
            ? meetingPatternService.listForSection(sectionId)
            : meetingPatternService.listForRoom(roomId);
        return ResponseEntity.ok(patterns.stream().map(MeetingPatternController::toResponse).toList());
    }

    public record UpdateRequest(short dayOfWeek, LocalTime startTime, LocalTime endTime, UUID roomId,
                                 LocalDate effectiveFrom, LocalDate effectiveTo) {
    }

    @PutMapping("/{meetingPatternId}")
    public ResponseEntity<?> update(@PathVariable UUID meetingPatternId, @RequestBody UpdateRequest request) {
        var result = meetingPatternService.update(meetingPatternId, request.dayOfWeek(), request.startTime(),
            request.endTime(), request.roomId(), request.effectiveFrom(), request.effectiveTo());
        return switch (result) {
            case MeetingPatternService.UpdateResult.Success ignored -> ResponseEntity.noContent().build();
            case MeetingPatternService.UpdateResult.NotFound ignored -> ResponseEntity.status(HttpStatus.NOT_FOUND).build();
            case MeetingPatternService.UpdateResult.RoomNotFound ignored ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("room not found"));
            case MeetingPatternService.UpdateResult.TenantMismatch ignored ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("room belongs to a different tenant"));
            case MeetingPatternService.UpdateResult.InvalidPatternData invalid ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse(invalid.message()));
            case MeetingPatternService.UpdateResult.RoomOverlap ignored ->
                ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse("this room is already booked for an overlapping time"));
        };
    }

    @DeleteMapping("/{meetingPatternId}")
    public ResponseEntity<?> remove(@PathVariable UUID meetingPatternId) {
        return switch (meetingPatternService.remove(meetingPatternId)) {
            case SUCCESS -> ResponseEntity.noContent().build();
            case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        };
    }

    public record ErrorResponse(String message) {
    }
}
