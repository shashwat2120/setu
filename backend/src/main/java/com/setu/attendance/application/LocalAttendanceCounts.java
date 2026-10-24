package com.setu.attendance.application;

import com.setu.shared.attendancefacts.AttendanceCounts;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The attendance counts, answered from this module's own records. Used wherever the attendance module itself runs (the monolith, and the Attendance service, which answers
 * its internal endpoint with it). Always present; {@code AttendanceCountsRouter} sends a question to it unless this process is set up to ask the Attendance service
 * ({@code setu.attendance.url}, {@code RemoteAttendanceCounts}), and even then while a first-start seeding is running ({@code LocalReads}).
 */
@Component
public class LocalAttendanceCounts implements AttendanceCounts {

    private final AttendanceRecordService attendanceRecordService;

    public LocalAttendanceCounts(AttendanceRecordService attendanceRecordService) {
        this.attendanceRecordService = attendanceRecordService;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, Counts> forEnrollments(Collection<UUID> enrollmentIds) {
        Map<UUID, Counts> counts = new LinkedHashMap<>();
        for (UUID id : enrollmentIds) {
            var pct = attendanceRecordService.percentageForEnrollmentUnchecked(id);
            counts.put(id, new Counts(pct.presentOrLate(), pct.total()));
        }
        return counts;
    }
}
