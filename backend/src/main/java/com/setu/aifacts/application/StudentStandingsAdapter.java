package com.setu.aifacts.application;

import com.setu.attendance.application.AttendanceRecordService;
import com.setu.enrollment.application.EnrollmentService;
import com.setu.enrollment.application.StudentProgramService;
import com.setu.record.application.AcademicRecordService;
import com.setu.shared.career.StudentStandings;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Answers {@link StudentStandings} for Career: the CGPA from the academic record, the attendance across the sections the student is enrolled in, and their active
 * program. AI facts already reads all three modules, so it is the one place that can answer without Career depending on any of them. No authorization of its own: the
 * caller (a student applying for themselves, an officer previewing eligibility) has authorized its own operation.
 */
@Component
class StudentStandingsAdapter implements StudentStandings {

    private final AcademicRecordService recordService;
    private final EnrollmentService enrollmentService;
    private final AttendanceRecordService attendanceRecordService;
    private final StudentProgramService studentProgramService;

    StudentStandingsAdapter(AcademicRecordService recordService, EnrollmentService enrollmentService, AttendanceRecordService attendanceRecordService,
                            StudentProgramService studentProgramService) {
        this.recordService = recordService;
        this.enrollmentService = enrollmentService;
        this.attendanceRecordService = attendanceRecordService;
        this.studentProgramService = studentProgramService;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Standing> of(UUID studentId) {
        long presentOrLate = 0;
        long total = 0;
        for (var ref : enrollmentService.listActiveEnrollmentRefsForStudent(studentId)) {
            var pct = attendanceRecordService.percentageForEnrollmentUnchecked(ref.enrollmentId());
            presentOrLate += pct.presentOrLate();
            total += pct.total();
        }
        Double attendance = total == 0 ? null : Math.round((presentOrLate * 10000.0) / total) / 100.0;
        return Optional.of(new Standing(recordService.gpaUnchecked(studentId), attendance, studentProgramService.activeProgramIdUnchecked(studentId).orElse(null)));
    }
}
