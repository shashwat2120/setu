package com.setu.attendance.infrastructure;

import com.setu.attendance.domain.StaffAttendancePolicy;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface StaffAttendancePolicyRepository extends JpaRepository<StaffAttendancePolicy, UUID> {

    /** The college's current policy = its highest version. */
    Optional<StaffAttendancePolicy> findFirstByTenantIdOrderByVersionDesc(UUID tenantId);
}
