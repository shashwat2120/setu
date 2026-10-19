package com.setu.attendance.infrastructure.web;

import com.setu.attendance.application.AttendanceSessionService;
import com.setu.attendance.domain.AttendanceSession;
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
import java.util.List;
import java.util.UUID;

/** Attendance session lifecycle (Day 9 §2-3) — see AttendanceSessionService's javadoc. */
@RestController
@RequestMapping("/attendance/sessions")
public class AttendanceSessionController {

    private final AttendanceSessionService attendanceSessionService;

    public AttendanceSessionController(AttendanceSessionService attendanceSessionService) {
        this.attendanceSessionService = attendanceSessionService;
    }

    /** {@code latitude} and {@code longitude} are the classroom position from the instructor's device; optional. */
    public record OpenRequest(UUID sectionId, UUID meetingId, Instant endsAt, Double latitude, Double longitude) {
    }

    public record OpenResponse(UUID sessionId, String qrToken, Instant qrExpiresAt) {
    }

    @PostMapping
    public ResponseEntity<?> open(@RequestBody OpenRequest request) {
        var result = attendanceSessionService.open(request.sectionId(), request.meetingId(), request.endsAt(),
            request.latitude(), request.longitude());
        return switch (result) {
            case AttendanceSessionService.OpenResult.Success r -> ResponseEntity.status(HttpStatus.CREATED)
                .body(new OpenResponse(r.sessionId(), r.rawToken(), r.qrExpiresAt()));
            case AttendanceSessionService.OpenResult.SectionNotFound ignored ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("section not found"));
            case AttendanceSessionService.OpenResult.MeetingNotFound ignored ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("meeting not found"));
            case AttendanceSessionService.OpenResult.MeetingSectionMismatch ignored ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("meeting does not belong to this section"));
            case AttendanceSessionService.OpenResult.TenantMismatch ignored ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("meeting belongs to a different tenant"));
            case AttendanceSessionService.OpenResult.InvalidSessionData invalid ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse(invalid.message()));
            case AttendanceSessionService.OpenResult.SessionAlreadyOpenForMeeting ignored ->
                ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse("an attendance session is already open for this meeting"));
        };
    }

    public record SessionResponse(UUID id, UUID tenantId, UUID sectionId, UUID meetingId, Instant startedAt,
                                   Instant endsAt, String status) {
    }

    @GetMapping("/{sessionId}")
    public ResponseEntity<?> get(@PathVariable UUID sessionId) {
        return attendanceSessionService.get(sessionId)
            .map(AttendanceSessionController::toResponse)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }

    private static SessionResponse toResponse(AttendanceSession s) {
        return new SessionResponse(s.id(), s.tenantId(), s.sectionId(), s.meetingId(), s.startedAt(), s.endsAt(), s.status().name());
    }

    @GetMapping
    public ResponseEntity<List<SessionResponse>> listForSection(@RequestParam UUID sectionId) {
        return ResponseEntity.ok(attendanceSessionService.listForSection(sectionId).stream()
            .map(AttendanceSessionController::toResponse).toList());
    }

    public record QrResponse(String qrToken, Instant qrExpiresAt) {
    }

    /** Always issues a fresh rotation — see AttendanceSessionService's javadoc for why. */
    @GetMapping("/{sessionId}/qr")
    public ResponseEntity<?> getActiveQr(@PathVariable UUID sessionId) {
        return rotate(sessionId);
    }

    @PostMapping("/{sessionId}/qr/rotate")
    public ResponseEntity<?> rotate(@PathVariable UUID sessionId) {
        var result = attendanceSessionService.rotateQr(sessionId);
        return switch (result) {
            case AttendanceSessionService.RotateResult.Success r -> ResponseEntity.ok(new QrResponse(r.rawToken(), r.qrExpiresAt()));
            case AttendanceSessionService.RotateResult.NotFound ignored -> ResponseEntity.status(HttpStatus.NOT_FOUND).build();
            case AttendanceSessionService.RotateResult.SessionClosed ignored ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("attendance session is closed"));
        };
    }

    public record CloseResponse(int absentRecordsGenerated) {
    }

    @PostMapping("/{sessionId}/close")
    public ResponseEntity<?> close(@PathVariable UUID sessionId) {
        var result = attendanceSessionService.close(sessionId);
        return switch (result) {
            case AttendanceSessionService.CloseResult.Success r -> ResponseEntity.ok(new CloseResponse(r.absentRecordsGenerated()));
            case AttendanceSessionService.CloseResult.NotFound ignored -> ResponseEntity.status(HttpStatus.NOT_FOUND).build();
            case AttendanceSessionService.CloseResult.InvalidTransition ignored ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("invalid status transition"));
        };
    }

    public record ErrorResponse(String message) {
    }
}
