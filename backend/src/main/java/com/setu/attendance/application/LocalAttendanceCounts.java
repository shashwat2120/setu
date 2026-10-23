package com.setu.attendance.application;

import com.setu.shared.attendancefacts.AttendanceCounts;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The attendance counts, answered from this module's own records. Used wherever the attendance module itself runs (the monolith, and the Attendance service, which answers
 * its internal endpoint with it). Off when {@code setu.attendance.url} is set: that process asks the Attendance service instead ({@code RemoteAttendanceCounts}).
 */
@Component
@ConditionalOnExpression("'${setu.attendance.url:}' == ''")
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
