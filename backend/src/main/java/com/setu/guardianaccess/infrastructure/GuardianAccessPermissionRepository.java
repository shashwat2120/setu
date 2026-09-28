package com.setu.guardianaccess.infrastructure;

import com.setu.guardianaccess.domain.GuardianAccessPermission;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface GuardianAccessPermissionRepository extends JpaRepository<GuardianAccessPermission, UUID> {

    List<GuardianAccessPermission> findByGrantId(UUID grantId);
}
