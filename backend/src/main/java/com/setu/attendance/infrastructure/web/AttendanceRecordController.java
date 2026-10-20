package com.setu.attendance.infrastructure.web;

import com.setu.attendance.application.AttendanceRecordService;
import com.setu.attendance.domain.AttendanceRecord;
import com.setu.attendance.domain.AttendanceRecordStatus;
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

/** Attendance scan/record/correction (Day 9 §4-5, §10) — see AttendanceRecordService's javadoc. */
@RestController
@RequestMapping("/attendance")
public class AttendanceRecordController {

    private final AttendanceRecordService attendanceRecordService;

    public AttendanceRecordController(AttendanceRecordService attendanceRecordService) {
        this.attendanceRecordService = attendanceRecordService;
    }

    /** The position fields are optional: a student who declines, or a device without location, scans exactly as before. */
    public record ScanRequest(UUID sessionId, String qrToken, Double latitude, Double longitude, Double accuracyMeters) {
    }

    /** {@code locationStatus} (NEAR, FAR, NO_LOCATION, NO_REFERENCE) and the numbers are null for records made before check-in location existed. */
    public record RecordResponse(UUID id, UUID attendanceSessionId, UUID enrollmentId, String status,
                                  Instant markedAt, String source, UUID correctedBy,
                                  String locationStatus, Integer locationDistanceMeters, Integer locationAccuracyMeters) {
    }

    private static RecordResponse toResponse(AttendanceRecord r) {
        return new RecordResponse(r.id(), r.attendanceSessionId(), r.enrollmentId(), r.status().name(), r.markedAt(),
            r.source().name(), r.correctedBy(), r.locationStatus() == null ? null : r.locationStatus().name(),
            r.locationDistanceMeters(), r.locationAccuracyMeters());
    }

    @PostMapping("/scan")
    public ResponseEntity<?> scan(@RequestBody ScanRequest request) {
        var result = attendanceRecordService.scan(request.sessionId(), request.qrToken(),
            new AttendanceRecordService.LocationReport(request.latitude(), request.longitude(), request.accuracyMeters()));
        return switch (result) {
            case AttendanceRecordService.ScanResult.Success r -> ResponseEntity.status(HttpStatus.CREATED)
                .body(new ScanResponse(r.recordId(), "PRESENT", r.locationStatus() == null ? null : r.locationStatus().name()));
            case AttendanceRecordService.ScanResult.AlreadyRecorded r -> ResponseEntity.ok(new ScanResponse(r.recordId(), r.status().name(), null));
            case AttendanceRecordService.ScanResult.SessionNotFound ignored ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("attendance session not found"));
            case AttendanceRecordService.ScanResult.StudentNotFound ignored ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("student profile not found"));
            case AttendanceRecordService.ScanResult.SessionNotOpen ignored ->
                ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse("attendance session is not open"));
            case AttendanceRecordService.ScanResult.InvalidToken ignored ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("invalid QR token"));
            case AttendanceRecordService.ScanResult.ExpiredToken ignored ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("QR token has expired"));
            case AttendanceRecordService.ScanResult.NotEnrolled ignored ->
                ResponseEntity.status(HttpStatus.FORBIDDEN).body(new ErrorResponse("no active enrollment in this section"));
        };
    }

    /** {@code locationStatus} is how the position compared with the classroom, for the student's own confirmation; null on a repeat scan. */
    public record ScanResponse(UUID recordId, String status, String locationStatus) {
    }

    @GetMapping("/sessions/{sessionId}/records")
    public ResponseEntity<List<RecordResponse>> listForSession(@PathVariable UUID sessionId, @RequestParam UUID tenantId) {
        return ResponseEntity.ok(attendanceRecordService.listForSession(sessionId, tenantId).stream()
            .map(AttendanceRecordController::toResponse).toList());
    }

    public record PercentageResponse(long presentOrLate, long total, double percentage) {
    }

    @GetMapping("/enrollments/{enrollmentId}/percentage")
    public ResponseEntity<PercentageResponse> percentage(@PathVariable UUID enrollmentId) {
        var p = attendanceRecordService.percentageForEnrollment(enrollmentId);
        return ResponseEntity.ok(new PercentageResponse(p.presentOrLate(), p.total(), p.percentage()));
    }

    /**
     * An enrollment's recent check-ins, newest first: the student's own history, the section staff's view of one student. A guardian gets the records
     * without where the student checked in from.
     */
    @GetMapping("/enrollments/{enrollmentId}/records")
    public ResponseEntity<List<RecordResponse>> recentForEnrollment(@PathVariable UUID enrollmentId) {
        var history = attendanceRecordService.recentForEnrollment(enrollmentId);
        return ResponseEntity.ok(history.records().stream().map(AttendanceRecordController::toResponse)
            .map(r -> history.includeLocation() ? r : new RecordResponse(r.id(), r.attendanceSessionId(), r.enrollmentId(), r.status(), r.markedAt(),
                r.source(), r.correctedBy(), null, null, null))
            .toList());
    }

    public record CorrectRequest(AttendanceRecordStatus status, String reason) {
    }

    @PostMapping("/records/{recordId}/correct")
    public ResponseEntity<?> correct(@PathVariable UUID recordId, @RequestBody CorrectRequest request) {
        var result = attendanceRecordService.correct(recordId, request.status(), request.reason());
        return switch (result) {
            case AttendanceRecordService.CorrectResult.Success ignored -> ResponseEntity.noContent().build();
            case AttendanceRecordService.CorrectResult.NotFound ignored -> ResponseEntity.status(HttpStatus.NOT_FOUND).build();
            case AttendanceRecordService.CorrectResult.SessionStillOpen ignored ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("cannot correct a record while its session is still open"));
            case AttendanceRecordService.CorrectResult.VersionConflict ignored ->
                ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse("this record was modified concurrently — re-fetch and retry"));
        };
    }

    public record ErrorResponse(String message) {
    }
}
