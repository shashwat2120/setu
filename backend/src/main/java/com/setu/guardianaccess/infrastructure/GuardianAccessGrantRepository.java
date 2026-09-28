package com.setu.guardianaccess.infrastructure;

import com.setu.guardianaccess.domain.GuardianAccessGrant;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface GuardianAccessGrantRepository extends JpaRepository<GuardianAccessGrant, UUID> {

    List<GuardianAccessGrant> findByGuardianIdAndStudentIdAndTenantId(UUID guardianId, UUID studentId, UUID tenantId);

    List<GuardianAccessGrant> findByGuardianId(UUID guardianId);

    List<GuardianAccessGrant> findByStudentIdAndTenantId(UUID studentId, UUID tenantId);
}
