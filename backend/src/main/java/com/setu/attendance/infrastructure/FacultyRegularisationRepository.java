package com.setu.attendance.infrastructure;

import com.setu.attendance.domain.FacultyRegularisation;
import com.setu.attendance.domain.RegularisationStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FacultyRegularisationRepository extends JpaRepository<FacultyRegularisation, UUID> {

    Optional<FacultyRegularisation> findByFacultyIdAndAttendanceDateAndStatus(UUID facultyId, LocalDate attendanceDate, RegularisationStatus status);

    List<FacultyRegularisation> findTop100ByFacultyIdOrderByCreatedAtDesc(UUID facultyId);

    List<FacultyRegularisation> findTop200ByTenantIdAndStatusOrderByCreatedAtAsc(UUID tenantId, RegularisationStatus status);

    List<FacultyRegularisation> findTop200ByTenantIdOrderByCreatedAtDesc(UUID tenantId);
}
