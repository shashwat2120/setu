package com.setu.identity.infrastructure.authorization;

import com.setu.directory.application.DirectoryService;
import com.setu.guardianaccess.application.GuardianAccessService;
import com.setu.identity.TestAuthentication;
import com.setu.identity.application.UserRegistrationService;
import com.setu.identity.domain.Person;
import com.setu.identity.domain.RoleCode;
import com.setu.identity.domain.UserRole;
import com.setu.identity.infrastructure.PersonRepository;
import com.setu.identity.infrastructure.RoleRepository;
import com.setu.identity.infrastructure.UserRoleRepository;
import com.setu.institution.application.CollegeDirectoryService;
import com.setu.shared.authorization.AccessDecision;
import com.setu.shared.authorization.AuthorizationContext;
import com.setu.shared.authorization.ResourceRef;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Day 4 instruction §5 and test-matrix items 7-10: a relationship alone
 * never grants access — only active grant + specific permission + tenant +
 * target student, all together, does. Also covers item 9's explicit example
 * ("revoked guardian grant immediately blocks access") as a workflow-state
 * authorization case.
 * <p>
 * Grant/revoke calls run as a separate COLLEGE_ADMIN actor, not the
 * guardian themselves — Day 6 §8 requires {@code granted_by} to be an
 * authorized principal, and (separately) a guardian must never be able to
 * grant themselves access; using the same fixture guardian for both roles
 * would test neither rule properly.
 */
@SpringBootTest
@Transactional
class GuardianDelegationAuthorizationTests {

    @Autowired
    AuthorizationContext authorizationContext;
    @Autowired
    UserRegistrationService userRegistrationService;
    @Autowired
    UserRoleRepository userRoleRepository;
    @Autowired
    RoleRepository roleRepository;
    @Autowired
    PersonRepository personRepository;
    @Autowired
    DirectoryService directoryService;
    @Autowired
    GuardianAccessService guardianAccessService;
    @Autowired
    CollegeDirectoryService collegeDirectoryService;

    @AfterEach
    void clearSecurityContext() {
        TestAuthentication.clear();
    }

    private record Fixture(UUID tenantId, UUID collegeAdminUserId, UUID guardianUserId, UUID guardianId,
                            UUID studentProfileId) {
    }

    private Fixture newFixture() {
        UUID tenant = collegeDirectoryService.provisionCollege(
            "G" + UUID.randomUUID().toString().substring(0, 8), "Guardian Test College", "Asia/Kolkata");

        var adminOutcome = (UserRegistrationService.Outcome.Created)
            userRegistrationService.register("admin-" + UUID.randomUUID() + "@example.com");
        UUID adminUserId = adminOutcome.userId();
        UUID adminRoleId = roleRepository.findByCode(RoleCode.COLLEGE_ADMIN).orElseThrow().id();
        userRoleRepository.save(new UserRole(UUID.randomUUID(), adminUserId, adminRoleId, tenant, Instant.now(), null));

        var guardianOutcome = (UserRegistrationService.Outcome.Created)
            userRegistrationService.register("guardian-" + UUID.randomUUID() + "@example.com");
        UUID guardianUserId = guardianOutcome.userId();

        Person guardianPerson = Person.withLegalName(UUID.randomUUID(), guardianUserId, "Test Guardian", Instant.now());
        personRepository.save(guardianPerson);
        UUID guardianId = directoryService.provisionGuardian(tenant, guardianPerson.id(), guardianUserId);

        Person studentPerson = Person.withLegalName(UUID.randomUUID(), null, "Test Student", Instant.now());
        personRepository.save(studentPerson);
        UUID studentProfileId = directoryService.provisionStudentProfile(
            tenant, studentPerson.id(), "ADM-" + UUID.randomUUID().toString().substring(0, 6), guardianUserId);

        UUID guardianRoleId = roleRepository.findByCode(RoleCode.GUARDIAN).orElseThrow().id();
        userRoleRepository.save(new UserRole(UUID.randomUUID(), guardianUserId, guardianRoleId, tenant, Instant.now(), null));

        return new Fixture(tenant, adminUserId, guardianUserId, guardianId, studentProfileId);
    }

    private ResourceRef gradesFor(Fixture fixture) {
        return ResourceRef.of("academic_record", UUID.randomUUID(), fixture.tenantId())
            .delegatableForStudent(fixture.studentProfileId());
    }

    private GuardianAccessService.GrantResult grantAsAdmin(Fixture fixture, Instant expiresAt, Set<String> permissions) {
        TestAuthentication.authenticateAs(fixture.collegeAdminUserId());
        var result = guardianAccessService.grantAccess(fixture.studentProfileId(), fixture.guardianId(), expiresAt, permissions);
        TestAuthentication.clear();
        return result;
    }

    // --- 7. guardian without grant ---
    @Test
    void guardianWithoutAnyGrantIsDenied() {
        Fixture fixture = newFixture();
        TestAuthentication.authenticateAs(fixture.guardianUserId());

        var decision = authorizationContext.can("VIEW_GRADES", gradesFor(fixture));

        assertThat(decision).isInstanceOf(AccessDecision.Deny.class);
    }

    // --- 8. guardian expired grant ---
    @Test
    void guardianWithExpiredGrantIsDenied() throws InterruptedException {
        Fixture fixture = newFixture();
        // guardian_access_grants' own CHECK constraint (V16) requires
        // expires_at > granted_at — a grant can never be CREATED
        // already-expired, only lapse afterward. So this proves the real
        // "expired" path (time passing past a validly-created expiry), not
        // a synthetic already-past value the schema wouldn't even accept.
        Instant expiresSoon = Instant.now().plusMillis(200);
        grantAsAdmin(fixture, expiresSoon, Set.of("VIEW_GRADES"));
        TestAuthentication.authenticateAs(fixture.guardianUserId());
        assertThat(authorizationContext.can("VIEW_GRADES", gradesFor(fixture)).isAllowed()).isTrue();

        Thread.sleep(300);

        var decision = authorizationContext.can("VIEW_GRADES", gradesFor(fixture));

        assertThat(decision).isInstanceOf(AccessDecision.Deny.class);
    }

    // --- 9. guardian revoked grant ("revoked guardian grant immediately blocks access") ---
    @Test
    void guardianWithRevokedGrantIsDenied() {
        Fixture fixture = newFixture();
        var granted = (GuardianAccessService.GrantResult.Success) grantAsAdmin(fixture, null, Set.of("VIEW_GRADES"));
        TestAuthentication.authenticateAs(fixture.guardianUserId());
        assertThat(authorizationContext.can("VIEW_GRADES", gradesFor(fixture)).isAllowed()).isTrue();
        TestAuthentication.clear();

        TestAuthentication.authenticateAs(fixture.collegeAdminUserId());
        var revoked = guardianAccessService.revokeAccess(granted.grantId(), "no longer applicable");
        assertThat(revoked).isEqualTo(GuardianAccessService.RevokeResult.SUCCESS);
        TestAuthentication.clear();

        TestAuthentication.authenticateAs(fixture.guardianUserId());
        var decisionAfterRevoke = authorizationContext.can("VIEW_GRADES", gradesFor(fixture));
        assertThat(decisionAfterRevoke).isInstanceOf(AccessDecision.Deny.class);
    }

    // --- 10. wrong guardian permission ---
    @Test
    void guardianGrantedOnlyOnePermissionCannotUseAnother() {
        Fixture fixture = newFixture();
        grantAsAdmin(fixture, null, Set.of("VIEW_GRADES")); // NOT VIEW_ATTENDANCE
        TestAuthentication.authenticateAs(fixture.guardianUserId());

        var gradesDecision = authorizationContext.can("VIEW_GRADES", gradesFor(fixture));
        assertThat(gradesDecision.isAllowed()).isTrue();

        ResourceRef attendance = ResourceRef.of("attendance", UUID.randomUUID(), fixture.tenantId())
            .delegatableForStudent(fixture.studentProfileId());
        var attendanceDecision = authorizationContext.can("VIEW_ATTENDANCE", attendance);
        assertThat(attendanceDecision).isInstanceOf(AccessDecision.Deny.class);
    }

    @Test
    void guardianAccessingAnUnrelatedStudentIsDenied() {
        Fixture fixture = newFixture();
        grantAsAdmin(fixture, null, Set.of("VIEW_GRADES"));
        TestAuthentication.authenticateAs(fixture.guardianUserId());

        ResourceRef unrelatedStudentRecord = ResourceRef.of("academic_record", UUID.randomUUID(), fixture.tenantId())
            .delegatableForStudent(UUID.randomUUID()); // some other student, no grant exists for them

        var decision = authorizationContext.can("VIEW_GRADES", unrelatedStudentRecord);

        assertThat(decision).isInstanceOf(AccessDecision.Deny.class);
    }

    @Test
    void aRelationshipRecordAloneNeverImpliesAccess_noGrantAtAll() {
        // Even a fully set up guardian (role + directory.guardians row) with
        // ZERO guardian_access_grants rows must be denied — the grant is
        // what confers access, never the relationship/role alone.
        Fixture fixture = newFixture();
        TestAuthentication.authenticateAs(fixture.guardianUserId());

        var decision = authorizationContext.can("VIEW_GRADES", gradesFor(fixture));

        assertThat(decision).isInstanceOf(AccessDecision.Deny.class);
    }

    // --- guardian cannot grant themselves access (Day 6 §8) ---
    @Test
    void guardianCannotGrantThemselvesAccess() {
        Fixture fixture = newFixture();
        // The guardian is also, implausibly, granted MANAGE_GUARDIAN_ACCESS
        // directly (simulating a misconfigured/compromised principal) to
        // prove the self-grant check is a real, separate guard — not merely
        // an artifact of the guardian usually lacking the permission.
        UUID adminRoleId = roleRepository.findByCode(RoleCode.COLLEGE_ADMIN).orElseThrow().id();
        userRoleRepository.save(new UserRole(UUID.randomUUID(), fixture.guardianUserId(), adminRoleId,
            fixture.tenantId(), Instant.now(), null));
        TestAuthentication.authenticateAs(fixture.guardianUserId());

        var result = guardianAccessService.grantAccess(fixture.studentProfileId(), fixture.guardianId(), null, Set.of("VIEW_GRADES"));

        assertThat(result).isInstanceOf(GuardianAccessService.GrantResult.GuardianCannotGrantSelf.class);
    }

    // --- unsupported permission rejected (Day 6 §8) ---
    @Test
    void grantWithUnsupportedPermissionIsRejected() {
        Fixture fixture = newFixture();

        var result = grantAsAdmin(fixture, null, Set.of("MANAGE_PLATFORM"));

        assertThat(result).isInstanceOf(GuardianAccessService.GrantResult.UnsupportedPermission.class);
    }
}
