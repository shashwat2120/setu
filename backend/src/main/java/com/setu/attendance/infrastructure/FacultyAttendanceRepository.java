package com.setu.attendance.infrastructure;

import com.setu.attendance.domain.FacultyAttendance;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FacultyAttendanceRepository extends JpaRepository<FacultyAttendance, UUID> {

    Optional<FacultyAttendance> findByFacultyIdAndAttendanceDate(UUID facultyId, LocalDate attendanceDate);

    List<FacultyAttendance> findByFacultyIdAndAttendanceDateBetweenOrderByAttendanceDateDesc(UUID facultyId, LocalDate from, LocalDate to);

    List<FacultyAttendance> findByTenantIdAndAttendanceDate(UUID tenantId, LocalDate attendanceDate);
}
