package com.setu.attendance.infrastructure.web;

import com.setu.shared.attendancefacts.AttendanceCounts;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The authoritative attendance counts of a set of enrollments, for the services that need them to compute something else (the risk engine, the AI advisor's attendance
 * tool, Career's standing) and may not read this service's schema. Answers from the attendance records themselves and nothing else.
 * <p>
 * Internal only: {@code /internal/**} requires the service-to-service token ({@code InternalCallAuthFilter}) and is not routed by the gateway. No per-person
 * authorization here by design: the caller has authorized its own outer operation, exactly as with the in-process method this replaces.
 */
@RestController
@org.springframework.boot.autoconfigure.condition.ConditionalOnExpression("'${setu.attendance.url:}' == ''")
@RequestMapping("/internal/attendance")
public class InternalAttendanceCountsController {

    /** More than a student's enrollments and more than one request carries; a bigger ask is a bug in the caller, refused rather than answered slowly. */
    static final int MAX_ENROLLMENTS = 100;

    public record Row(UUID enrollmentId, long presentOrLate, long total) {
    }

    public record Answer(List<Row> counts) {
    }

    private final AttendanceCounts counts;

    public InternalAttendanceCountsController(AttendanceCounts counts) {
        this.counts = counts;
    }

    @GetMapping("/counts")
    public ResponseEntity<Answer> counts(@RequestParam List<UUID> enrollmentIds) {
        var ids = new LinkedHashSet<>(enrollmentIds);
        if (ids.isEmpty() || ids.size() > MAX_ENROLLMENTS) {
            return ResponseEntity.badRequest().build();
        }
        Map<UUID, AttendanceCounts.Counts> found = counts.forEnrollments(ids);
        return ResponseEntity.ok(new Answer(ids.stream()
            .map(id -> {
                var c = found.getOrDefault(id, AttendanceCounts.Counts.NONE);
                return new Row(id, c.presentOrLate(), c.total());
            }).toList()));
    }
}
