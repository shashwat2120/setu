package com.setu.attendance.infrastructure.web;

import com.setu.attendance.application.FacultyAttendanceService;
import com.setu.attendance.application.FacultyAttendanceService.RecordResult;
import com.setu.attendance.application.StaffAttendancePolicyService;
import com.setu.attendance.domain.FacultyAttendance;
import com.setu.attendance.domain.FacultyAttendanceStatus;
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

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * A faculty member's own daily attendance (check in / out) and the administrator's view and corrections — see
 * {@link FacultyAttendanceService}. Who the caller is and which college they are in always come from the
 * authenticated principal; no request body ever carries a tenant or a faculty id for the self endpoints.
 */
@RestController
@RequestMapping("/staff-attendance")
public class FacultyAttendanceController {

    private final FacultyAttendanceService service;
    private final StaffAttendancePolicyService policyService;

    public FacultyAttendanceController(FacultyAttendanceService service, StaffAttendancePolicyService policyService) {
        this.service = service;
        this.policyService = policyService;
    }

    public record AttendanceResponse(UUID id, UUID tenantId, UUID facultyId, LocalDate attendanceDate, String status,
                                      Instant checkInAt, Instant checkOutAt, String source, UUID correctedBy,
                                      String correctionReason, Integer lateMinutes, Integer workedMinutes,
                                      Integer policyVersion, boolean shortHours) {
    }

    /** {@code date} is today in the college's timezone (so the UI never guesses it); {@code record} is null if nothing is recorded yet. */
    public record TodayResponse(LocalDate date, Instant serverTime, String timeZone, AttendanceResponse record) {
    }

    /** The rules in force for the caller's college; times are college-local HH:mm. {@code version} 0 means the built-in defaults (nothing saved yet). */
    public record PolicyResponse(int version, String shiftStart, String shiftEnd, int graceMinutes, int fullDayMinutes, int halfDayMinutes) {
    }

    public record PolicyRequest(String shiftStart, String shiftEnd, Integer graceMinutes, Integer fullDayMinutes, Integer halfDayMinutes) {
    }

    public record MarkRequest(String status, String reason) {
    }

    public record ErrorResponse(String message) {
    }

    private static AttendanceResponse toResponse(FacultyAttendance r) {
        return new AttendanceResponse(r.id(), r.tenantId(), r.facultyId(), r.attendanceDate(), r.status().name(),
            r.checkInAt(), r.checkOutAt(), r.source().name(), r.correctedBy(), r.correctionReason(), r.lateMinutes(),
            r.workedMinutes(), r.policyVersion(), r.shortHours());
    }

    // ---- self ----

    @GetMapping("/me/today")
    public ResponseEntity<TodayResponse> today() {
        LocalDate date = service.todayForCurrentTenant().orElse(LocalDate.now());
        String zone = service.zoneForCurrentTenant().map(java.time.ZoneId::getId).orElse("UTC");
        return ResponseEntity.ok(new TodayResponse(date, Instant.now(), zone,
            service.myToday().map(FacultyAttendanceController::toResponse).orElse(null)));
    }

    @PostMapping("/me/check-in")
    public ResponseEntity<?> checkIn() {
        return respond(service.checkIn(), HttpStatus.CREATED);
    }

    @PostMapping("/me/check-out")
    public ResponseEntity<?> checkOut() {
        return respond(service.checkOut(), HttpStatus.OK);
    }

    // ---- read ----

    @GetMapping("/faculty/{facultyId}")
    public ResponseEntity<?> history(@PathVariable UUID facultyId, @RequestParam LocalDate from, @RequestParam LocalDate to) {
        return service.history(facultyId, from, to)
            .<ResponseEntity<?>>map(rows -> ResponseEntity.ok(rows.stream().map(FacultyAttendanceController::toResponse).toList()))
            .orElse(ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse("faculty member not found")));
    }

    @GetMapping
    public ResponseEntity<List<AttendanceResponse>> listForDate(@RequestParam UUID tenantId, @RequestParam LocalDate date) {
        return ResponseEntity.ok(service.listForDate(tenantId, date).stream().map(FacultyAttendanceController::toResponse).toList());
    }

    // ---- admin ----

    @PutMapping("/faculty/{facultyId}/{date}")
    public ResponseEntity<?> mark(@PathVariable UUID facultyId, @PathVariable LocalDate date, @RequestBody MarkRequest request) {
        FacultyAttendanceStatus status;
        try {
            status = request.status() == null ? null : FacultyAttendanceStatus.valueOf(request.status().trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("status must be one of PRESENT, HALF_DAY, ABSENT, ON_LEAVE"));
        }
        return respond(service.markByAdmin(facultyId, date, status, request.reason()), HttpStatus.OK);
    }

    // ---- policy ----

    private static PolicyResponse toResponse(com.setu.attendance.domain.PolicyRules r) {
        return new PolicyResponse(r.version(), r.shiftStart().toString(), r.shiftEnd().toString(), r.graceMinutes(), r.fullDayMinutes(), r.halfDayMinutes());
    }

    @GetMapping("/policy")
    public ResponseEntity<?> policy() {
        return policyService.currentForCaller()
            .<ResponseEntity<?>>map(r -> ResponseEntity.ok(toResponse(r)))
            .orElse(ResponseEntity.status(HttpStatus.FORBIDDEN).body(new ErrorResponse("no college is associated with this account")));
    }

    @PutMapping("/policy")
    public ResponseEntity<?> updatePolicy(@RequestBody PolicyRequest body) {
        java.time.LocalTime start;
        java.time.LocalTime end;
        try {
            start = java.time.LocalTime.parse(body.shiftStart() == null ? "" : body.shiftStart().trim());
            end = java.time.LocalTime.parse(body.shiftEnd() == null ? "" : body.shiftEnd().trim());
        } catch (java.time.format.DateTimeParseException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("shift times must be HH:mm"));
        }
        if (body.graceMinutes() == null || body.fullDayMinutes() == null || body.halfDayMinutes() == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("grace, full-day and half-day minutes are required"));
        }
        return switch (policyService.update(start, end, body.graceMinutes(), body.fullDayMinutes(), body.halfDayMinutes())) {
            case StaffAttendancePolicyService.UpdateResult.Success ok -> ResponseEntity.ok(toResponse(ok.rules()));
            case StaffAttendancePolicyService.UpdateResult.InvalidRequest bad -> ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse(bad.message()));
            case StaffAttendancePolicyService.UpdateResult.NoChange same -> ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse("these values are already the current policy"));
            case StaffAttendancePolicyService.UpdateResult.Conflict ignored -> ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse("another administrator changed the policy at the same moment; reload and try again"));
            case StaffAttendancePolicyService.UpdateResult.NoCollege ignored -> ResponseEntity.status(HttpStatus.FORBIDDEN).body(new ErrorResponse("no college is associated with this account"));
        };
    }

    private ResponseEntity<?> respond(RecordResult result, HttpStatus successStatus) {
        return switch (result) {
            case RecordResult.Success s -> ResponseEntity.status(successStatus).body(toResponse(s.record()));
            case RecordResult.NotAFaculty ignored -> ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new ErrorResponse("only a faculty member can record their own attendance"));
            case RecordResult.ProfileNotActive p -> ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("your faculty profile is " + p.status() + ", so you cannot check in"));
            case RecordResult.AlreadyRecorded a -> ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("today is already recorded as " + a.record().status()
                    + (a.record().source().name().equals("ADMIN") ? " by an administrator; ask them to correct it" : "")));
            case RecordResult.NotCheckedIn ignored -> ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("you have not checked in today"));
            case RecordResult.AlreadyCheckedOut ignored -> ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("you have already checked out today"));
            case RecordResult.FacultyNotFound ignored -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("faculty member not found"));
            case RecordResult.InvalidRequest i -> ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse(i.message()));
        };
    }
}
