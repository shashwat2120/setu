package com.setu.aifacts.application;

import com.setu.enrollment.application.StudentProgramService;
import com.setu.shared.career.StudentCohorts;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/** Answers {@link StudentCohorts} for Career from Enrollment, which AI facts already reads; no authorization of its own (the caller has authorized its own operation). */
@Component
class StudentCohortsAdapter implements StudentCohorts {

    private final StudentProgramService studentProgramService;

    StudentCohortsAdapter(StudentProgramService studentProgramService) {
        this.studentProgramService = studentProgramService;
    }

    @Override
    public Map<UUID, Integer> activeStudentsByProgram(UUID tenantId) {
        return studentProgramService.activeStudentCountsByProgramUnchecked(tenantId);
    }

    @Override
    public Map<UUID, UUID> activeProgramOf(Collection<UUID> studentIds) {
        return studentProgramService.activeProgramsUnchecked(studentIds);
    }
}
