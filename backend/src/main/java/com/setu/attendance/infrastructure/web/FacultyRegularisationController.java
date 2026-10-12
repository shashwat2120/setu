package com.setu.attendance.infrastructure.web;

import com.setu.attendance.application.FacultyRegularisationService;
import com.setu.attendance.application.FacultyRegularisationService.Result;
import com.setu.attendance.domain.FacultyAttendanceStatus;
import com.setu.attendance.domain.FacultyRegularisation;
import com.setu.attendance.domain.RegularisationStatus;
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
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;

/**
 * "I forgot to check in" requests and the administrator's queue — see {@link FacultyRegularisationService}. Identity and
 * college always come from the authenticated principal; the body never carries a faculty id or tenant.
 */
@RestController
@RequestMapping("/staff-attendance/regularisations")
public class FacultyRegularisationController {

    private final FacultyRegularisationService service;

    public FacultyRegularisationController(FacultyRegularisationService service) {
        this.service = service;
    }

    /** {@code date} is yyyy-MM-dd; {@code checkInTime}/{@code checkOutTime} are optional HH:mm in the college's timezone. */
    public record SubmitRequest(String date, String status, String checkInTime, String checkOutTime, String reason) {
    }

    public record DecisionRequest(String note) {
    }

    public record RegularisationResponse(UUID id, UUID tenantId, UUID facultyId, LocalDate attendanceDate,
                                          String requestedStatus, Instant requestedCheckInAt, Instant requestedCheckOutAt,
                                          String reason, String status, UUID decidedBy, Instant decidedAt,
                                          String decisionNote, Instant createdAt) {
    }

    public record ErrorResponse(String message) {
    }

    private static RegularisationResponse toResponse(FacultyRegularisation r) {
        return new RegularisationResponse(r.id(), r.tenantId(), r.facultyId(), r.attendanceDate(), r.requestedStatus().name(),
            r.requestedCheckInAt(), r.requestedCheckOutAt(), r.reason(), r.status().name(), r.decidedBy(), r.decidedAt(),
            r.decisionNote(), r.createdAt());
    }

    // ---- faculty ----

    @PostMapping("/me")
    public ResponseEntity<?> request(@RequestBody SubmitRequest body) {
        LocalDate date;
        FacultyAttendanceStatus status;
        LocalTime in;
        LocalTime out;
        try {
            date = body.date() == null ? null : LocalDate.parse(body.date().trim());
            status = body.status() == null ? null : FacultyAttendanceStatus.valueOf(body.status().trim().toUpperCase());
            in = blank(body.checkInTime()) ? null : LocalTime.parse(body.checkInTime().trim());
            out = blank(body.checkOutTime()) ? null : LocalTime.parse(body.checkOutTime().trim());
        } catch (DateTimeParseException | IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("date must be yyyy-MM-dd, times HH:mm, status one of PRESENT, HALF_DAY, ON_LEAVE"));
        }
        return respond(service.request(date, status, in, out, body.reason()), HttpStatus.CREATED);
    }

    @GetMapping("/me")
    public ResponseEntity<List<RegularisationResponse>> mine() {
        return ResponseEntity.ok(service.myRequests().stream().map(FacultyRegularisationController::toResponse).toList());
    }

    @PostMapping("/{id}/withdraw")
    public ResponseEntity<?> withdraw(@PathVariable UUID id) {
        return respond(service.withdraw(id), HttpStatus.OK);
    }

    // ---- administrator ----

    /** {@code status} optional (PENDING, APPROVED, REJECTED, WITHDRAWN); omit for everything. */
    @GetMapping
    public ResponseEntity<?> list(@RequestParam UUID tenantId, @RequestParam(required = false) String status) {
        RegularisationStatus filter = null;
        if (!blank(status)) {
            try {
                filter = RegularisationStatus.valueOf(status.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new ErrorResponse("status must be one of PENDING, APPROVED, REJECTED, WITHDRAWN"));
            }
        }
        return ResponseEntity.ok(service.listForTenant(tenantId, filter).stream().map(FacultyRegularisationController::toResponse).toList());
    }

    @PostMapping("/{id}/approve")
    public ResponseEntity<?> approve(@PathVariable UUID id, @RequestBody(required = false) DecisionRequest body) {
        return respond(service.approve(id, body == null ? null : body.note()), HttpStatus.OK);
    }

    @PostMapping("/{id}/reject")
    public ResponseEntity<?> reject(@PathVariable UUID id, @RequestBody(required = false) DecisionRequest body) {
        return respond(service.reject(id, body == null ? null : body.note()), HttpStatus.OK);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private ResponseEntity<?> respond(Result result, HttpStatus successStatus) {
        return switch (result) {
            case Result.Success s -> ResponseEntity.status(successStatus).body(toResponse(s.request()));
            case Result.NotAFaculty ignored -> ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new ErrorResponse("only a faculty member can request a correction to their own attendance"));
            case Result.ProfileNotActive p -> ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("your faculty profile is " + p.status() + ", so you cannot submit requests"));
            case Result.InvalidRequest i -> ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse(i.message()));
            case Result.AlreadyPending ignored -> ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("a request for that day is already waiting for a decision"));
            case Result.NothingToChange ignored -> ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("that day is already recorded exactly as you are asking for"));
            case Result.NotFound ignored -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse("request not found"));
            case Result.NotPending n -> ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("this request has already been " + n.current().name().toLowerCase()));
            case Result.OwnRequest ignored -> ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new ErrorResponse("you cannot decide your own request; another administrator must"));
        };
    }
}
