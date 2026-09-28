package com.setu.identity.infrastructure;

import com.setu.identity.domain.RolePermission;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface RolePermissionRepository extends JpaRepository<RolePermission, UUID> {

    List<RolePermission> findByRoleIdIn(List<UUID> roleIds);
}
