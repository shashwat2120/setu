package com.setu.identity.infrastructure;

import com.setu.identity.domain.Role;
import com.setu.identity.domain.RoleCode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface RoleRepository extends JpaRepository<Role, UUID> {

    Optional<Role> findByCode(RoleCode code);
}
