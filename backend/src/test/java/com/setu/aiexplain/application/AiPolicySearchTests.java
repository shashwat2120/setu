package com.setu.aiexplain.application;

import com.setu.identity.TestAuthentication;
import com.setu.testsupport.Day14TestFixtures;
import com.setu.testsupport.Day7TestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Day 14 §11/§21: {@code searchCollegePolicy} — tenant isolation (never a
 * client-supplied tenant, resolved from the caller's own session via
 * {@link com.setu.shared.tenant.TenantContext}) and the document-ACL
 * filter (a restricted document is invisible to a caller who doesn't hold
 * its {@code requiredPermissionCode}, even within the SAME tenant).
 */
@SpringBootTest
class AiPolicySearchTests {

    @Autowired
    Day7TestFixtures fixtures;
    @Autowired
    Day14TestFixtures day14Fixtures;
    @Autowired
    AiToolService aiToolService;

    @AfterEach
    void clearSecurityContext() {
        TestAuthentication.clear();
    }

    @SuppressWarnings("unchecked")
    private List<AiToolService.PolicySearchResult> searchAs(UUID userId, String query) {
        TestAuthentication.authenticateAs(userId);
        try {
            return (List<AiToolService.PolicySearchResult>) aiToolService.searchCollegePolicy(query);
        } finally {
            TestAuthentication.clear();
        }
    }

    @Test
    void findsAPublicDocumentByKeyword() {
        var tenant = fixtures.provisionTenant("PS1");
        day14Fixtures.provisionPolicyDocument(tenant, "Attendance Policy",
            "Students must maintain at least seventy five percent attendance in every enrolled section.", null);

        var results = searchAs(tenant.adminUserId(), "attendance");

        assertThat(results).anyMatch(r -> r.title().equals("Attendance Policy"));
    }

    @Test
    void neverReturnsAnotherTenantsDocument() {
        var tenantA = fixtures.provisionTenant("PS2A");
        var tenantB = fixtures.provisionTenant("PS2B");
        day14Fixtures.provisionPolicyDocument(tenantA, "Grading Policy", "Grading uses a ten point scale for all courses.", null);

        var results = searchAs(tenantB.adminUserId(), "grading scale");

        assertThat(results).isEmpty();
    }

    @Test
    void restrictedDocumentIsInvisibleToACallerWithoutThePermission() {
        var tenant = fixtures.provisionTenant("PS3");
        UUID studentUserId = fixtures.provisionUserWithRole(tenant.tenantId(), com.setu.identity.domain.RoleCode.STUDENT, "PS3S");
        day14Fixtures.provisionPolicyDocument(tenant, "Internal Staff Circular",
            "This internal circular about faculty leave scheduling is for staff eyes only.", "VIEW_TENANT_DATA");

        var asStudent = searchAs(studentUserId, "staff leave scheduling");
        var asAdmin = searchAs(tenant.adminUserId(), "staff leave scheduling");

        assertThat(asStudent).isEmpty();
        assertThat(asAdmin).anyMatch(r -> r.title().equals("Internal Staff Circular"));
    }
}
