package com.setu.guardianaccess.application;

import com.setu.enrollment.application.CourseSectionService;
import com.setu.identity.TestAuthentication;
import com.setu.testsupport.Day10TestFixtures;
import com.setu.testsupport.Day13TestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Canonical spec §3, "Guardian access: explicit, scoped, revocable and audited" (AGENTS.md: a grant is an auditable row, revocation
 * takes immediate effect). The scoping and denial cases are covered by {@code GuardianDelegatedLookupTests} and the authorization
 * tests; this covers the lifecycle itself: granting and revoking are each audited with who did it, and a revoked grant stops working
 * on the very next request.
 */
@SpringBootTest
class GuardianGrantLifecycleTests {

    @Autowired
    Day13TestFixtures day13Fixtures;
    @Autowired
    Day10TestFixtures day10Fixtures;
    @Autowired
    GuardianAccessService guardianAccessService;
    @Autowired
    CourseSectionService courseSectionService;
    @Autowired
    JdbcClient jdbc;

    @AfterEach
    void clearSecurityContext() {
        TestAuthentication.clear();
    }

    private List<String> auditActions(UUID grantId) {
        return jdbc.sql("SELECT action FROM governance.audit_logs WHERE resource_id = :id ORDER BY created_at")
            .param("id", grantId).query(String.class).list();
    }

    @Test
    void grantingAndRevokingAreAuditedWithTheActorAndRevocationIsImmediate() {
        var s = day13Fixtures.provisionEnrolledScenario("GGL1");
        var guardian = day10Fixtures.provisionGuardian(s.tenant(), "GGL1G");

        TestAuthentication.authenticateAs(s.tenant().adminUserId());
        var granted = (GuardianAccessService.GrantResult.Success)
            guardianAccessService.grantAccess(s.student().studentId(), guardian.guardianId(), null, Set.of("VIEW_SCHEDULE"));
        UUID grantId = granted.grantId();
        TestAuthentication.clear();

        TestAuthentication.authenticateAs(guardian.guardianUserId());
        assertThat(courseSectionService.get(s.sectionId(), s.student().studentId())).as("works while the grant is active").isPresent();
        TestAuthentication.clear();

        TestAuthentication.authenticateAs(s.tenant().adminUserId());
        assertThat(guardianAccessService.revokeAccess(grantId, "no longer needed")).isEqualTo(GuardianAccessService.RevokeResult.SUCCESS);
        TestAuthentication.clear();

        TestAuthentication.authenticateAs(guardian.guardianUserId());
        assertThatThrownBy(() -> courseSectionService.get(s.sectionId(), s.student().studentId()))
            .as("the very next request after revocation").isInstanceOf(AccessDeniedException.class);

        assertThat(auditActions(grantId)).containsExactly("GUARDIAN_ACCESS_GRANTED", "GUARDIAN_ACCESS_REVOKED");
        List<UUID> actors = jdbc.sql("SELECT actor_user_id FROM governance.audit_logs WHERE resource_id = :id").param("id", grantId)
            .query(UUID.class).list();
        assertThat(actors).as("both entries name the administrator who acted").containsOnly(s.tenant().adminUserId());
    }

    @Test
    void aGuardianCannotRevokeOrGrantTheirOwnAccess() {
        var s = day13Fixtures.provisionEnrolledScenario("GGL2");
        var guardian = day10Fixtures.provisionGuardian(s.tenant(), "GGL2G");
        TestAuthentication.authenticateAs(s.tenant().adminUserId());
        var granted = (GuardianAccessService.GrantResult.Success)
            guardianAccessService.grantAccess(s.student().studentId(), guardian.guardianId(), null, Set.of("VIEW_SCHEDULE"));
        TestAuthentication.clear();

        TestAuthentication.authenticateAs(guardian.guardianUserId());
        assertThatThrownBy(() -> guardianAccessService.grantAccess(s.student().studentId(), guardian.guardianId(), null, Set.of("VIEW_GRADES")))
            .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> guardianAccessService.revokeAccess(granted.grantId(), "x")).isInstanceOf(AccessDeniedException.class);
    }
}
