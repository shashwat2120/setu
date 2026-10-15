package com.setu.academic.readmodel;

import com.setu.shared.authorization.RoleGrant;
import com.setu.shared.authorization.RoleGrantSource;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Roles and permissions from the Academic service's own read model of Identity's data ({@code institution.rm_role_grants} and
 * {@code rm_role_permissions}), so an authorization decision never calls Identity. An assignment counts at {@code now} when it has
 * started and not ended, the same rule Identity applies to its own table.
 */
public class ReadModelRoleGrantSource implements RoleGrantSource {

    private final JdbcClient jdbc;

    public ReadModelRoleGrantSource(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<RoleGrant> activeGrants(UUID userId, Instant now) {
        return jdbc.sql("""
                SELECT role_code, tenant_id FROM institution.rm_role_grants
                WHERE user_id = :userId AND starts_at <= :now AND (ends_at IS NULL OR ends_at > :now)
                """)
            .param("userId", userId).param("now", Timestamp.from(now))
            .query((rs, i) -> new RoleGrant(rs.getString("role_code"), rs.getObject("tenant_id", UUID.class)))
            .list();
    }

    @Override
    public Set<String> permissionsOf(Collection<String> roleCodes) {
        if (roleCodes.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(jdbc.sql("SELECT permission_code FROM institution.rm_role_permissions WHERE role_code IN (:codes)")
            .param("codes", List.copyOf(roleCodes))
            .query(String.class).list());
    }
}
