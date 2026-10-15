package com.setu.shared.readmodel;

import com.setu.shared.authorization.GuardianDelegationChecker;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/**
 * {@link GuardianDelegationChecker} for an extracted service, from its local copy of Academic's guardian grants. A delegation counts only
 * when all of it holds at once: this guardian's account, this student, this college, a grant that is not revoked and not expired, and the
 * specific permission asked for. Anything missing is "no".
 * <p>
 * The one honest difference from the monolith: a revocation reaches this copy when its event is applied (seconds), not in the same
 * transaction. Docs/contracts/extracted-services.md records this as a known bound and as the first thing the synchronous check in 6B.2 closes.
 */
public class ReadModelGuardianDelegationChecker implements GuardianDelegationChecker {

    private final JdbcClient jdbc;
    private final String grants;
    private final String permissions;

    public ReadModelGuardianDelegationChecker(JdbcClient jdbc, String schema) {
        this.jdbc = jdbc;
        this.grants = ReadModelSchema.table(schema, "rm_guardian_grants");
        this.permissions = ReadModelSchema.table(schema, "rm_guardian_grant_permissions");
    }

    @Override
    public boolean hasActiveDelegatedAccess(UUID guardianUserId, UUID studentProfileId, UUID tenantId, String permissionCode, Instant now) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM " + grants + " g JOIN " + permissions + " p ON p.grant_id = g.grant_id "
                + "WHERE g.guardian_user_id = :guardian AND g.student_id = :student AND g.tenant_id = :tenant "
                + "AND g.revoked_at IS NULL AND (g.expires_at IS NULL OR g.expires_at > :now) AND p.permission_code = :code)")
            .param("guardian", guardianUserId).param("student", studentProfileId).param("tenant", tenantId)
            .param("now", Timestamp.from(now)).param("code", permissionCode).query(Boolean.class).single();
    }
}
