package com.setu.identity.infrastructure.authorization;

import com.setu.identity.domain.Role;
import com.setu.identity.infrastructure.RolePermissionRepository;
import com.setu.identity.infrastructure.RoleRepository;
import com.setu.identity.infrastructure.UserRoleRepository;
import com.setu.shared.authorization.RoleGrant;
import com.setu.shared.authorization.RoleGrantSource;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** The roles and permissions straight from Identity's own tables: the authority that every other service's read model is a copy of. */
@Component
public class IdentityRoleGrantSource implements RoleGrantSource {

    private final UserRoleRepository userRoleRepository;
    private final RoleRepository roleRepository;
    private final RolePermissionRepository rolePermissionRepository;

    public IdentityRoleGrantSource(UserRoleRepository userRoleRepository, RoleRepository roleRepository,
                                    RolePermissionRepository rolePermissionRepository) {
        this.userRoleRepository = userRoleRepository;
        this.roleRepository = roleRepository;
        this.rolePermissionRepository = rolePermissionRepository;
    }

    @Override
    public List<RoleGrant> activeGrants(UUID userId, Instant now) {
        Map<UUID, Role> rolesById = roleRepository.findAll().stream().collect(Collectors.toMap(Role::id, r -> r));
        return userRoleRepository.findByUserId(userId).stream()
            .filter(ur -> ur.isActive(now) && rolesById.get(ur.roleId()) != null)
            .map(ur -> new RoleGrant(rolesById.get(ur.roleId()).code().name(), ur.tenantId()))
            .toList();
    }

    @Override
    public Set<String> permissionsOf(Collection<String> roleCodes) {
        List<UUID> roleIds = roleRepository.findAll().stream()
            .filter(r -> roleCodes.contains(r.code().name()))
            .map(Role::id)
            .toList();
        if (roleIds.isEmpty()) {
            return Set.of();
        }
        return rolePermissionRepository.findByRoleIdIn(roleIds).stream()
            .map(rp -> rp.permissionCode())
            .collect(Collectors.toSet());
    }
}
