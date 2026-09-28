package com.setu.shared.governance;

import com.setu.identity.TestAuthentication;
import com.setu.identity.application.UserRegistrationService;
import com.setu.identity.domain.RoleCode;
import com.setu.identity.domain.UserRole;
import com.setu.identity.infrastructure.RoleRepository;
import com.setu.identity.infrastructure.UserRoleRepository;
import com.setu.institution.application.CollegeDirectoryService;
import com.setu.institution.application.CollegeService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Day 16 §super-admin: {@link AuditLogReadService} — the read side of the append-only audit trail
 * {@link AuditLogWriter} already commits to on every sensitive mutation. Real rows, never hand-inserted:
 * every entry checked here was written by a genuine {@code CollegeService}/{@code RoleAssignmentService}
 * mutation earlier in the same test.
 */
@SpringBootTest
class AuditLogReadServiceTests {

    @Autowired
    AuditLogReadService auditLogReadService;
    @Autowired
    CollegeService collegeService;
    @Autowired
    CollegeDirectoryService collegeDirectoryService;
    @Autowired
    UserRegistrationService userRegistrationService;
    @Autowired
    UserRoleRepository userRoleRepository;
    @Autowired
    RoleRepository roleRepository;

    @AfterEach
    void clearSecurityContext() {
        TestAuthentication.clear();
    }

    private UUID newTenant() {
        return collegeDirectoryService.provisionCollege("AL" + UUID.randomUUID().toString().substring(0, 8),
            "Audit Log Test College", "Asia/Kolkata");
    }

    private UUID newSuperAdmin() {
        var outcome = (UserRegistrationService.Outcome.Created)
            userRegistrationService.register("audit-super-" + UUID.randomUUID() + "@example.com");
        UUID roleId = roleRepository.findByCode(RoleCode.SUPER_ADMIN).orElseThrow().id();
        userRoleRepository.save(new UserRole(UUID.randomUUID(), outcome.userId(), roleId, null, Instant.now(), null));
        return outcome.userId();
    }

    private UUID newCollegeAdmin(UUID tenantId) {
        var outcome = (UserRegistrationService.Outcome.Created)
            userRegistrationService.register("audit-admin-" + UUID.randomUUID() + "@example.com");
        UUID roleId = roleRepository.findByCode(RoleCode.COLLEGE_ADMIN).orElseThrow().id();
        userRoleRepository.save(new UserRole(UUID.randomUUID(), outcome.userId(), roleId, tenantId, Instant.now(), null));
        return outcome.userId();
    }

    @Test
    void superAdminSeesARealAuditEntryFromAGenuineMutation_platformWide() {
        UUID superAdmin = newSuperAdmin();
        TestAuthentication.authenticateAs(superAdmin);
        UUID collegeId = ((CollegeService.CreateResult.Success)
            collegeService.create("AL1" + UUID.randomUUID().toString().substring(0, 5), "Audited College", "Asia/Kolkata")).collegeId();

        var page = auditLogReadService.listRecent(null, PageRequest.of(0, 50));

        assertThat(page.getContent()).anyMatch(e -> e.resourceId().equals(collegeId) && e.action().equals("COLLEGE_CREATED"));
    }

    @Test
    void collegeAdminCannotViewThePlatformWideFeed() {
        UUID tenant = newTenant();
        UUID admin = newCollegeAdmin(tenant);
        TestAuthentication.authenticateAs(admin);

        assertThatThrownBy(() -> auditLogReadService.listRecent(null, PageRequest.of(0, 20)))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void collegeAdminCanViewTheirOwnTenantsFeed() {
        UUID tenant = newTenant();
        UUID admin = newCollegeAdmin(tenant);
        TestAuthentication.authenticateAs(admin);

        var page = auditLogReadService.listRecent(tenant, PageRequest.of(0, 20));

        assertThat(page).isNotNull();
    }

    @Test
    void unauthenticatedRoleCannotViewAnyAuditFeed() {
        UUID tenant = newTenant();
        var outcome = (UserRegistrationService.Outcome.Created)
            userRegistrationService.register("audit-student-" + UUID.randomUUID() + "@example.com");
        UUID roleId = roleRepository.findByCode(RoleCode.STUDENT).orElseThrow().id();
        userRoleRepository.save(new UserRole(UUID.randomUUID(), outcome.userId(), roleId, tenant, Instant.now(), null));
        TestAuthentication.authenticateAs(outcome.userId());

        assertThatThrownBy(() -> auditLogReadService.listRecent(tenant, PageRequest.of(0, 20)))
            .isInstanceOf(AccessDeniedException.class);
    }
}
