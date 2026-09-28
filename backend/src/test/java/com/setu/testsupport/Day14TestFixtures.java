package com.setu.testsupport;

import com.setu.identity.TestAuthentication;
import com.setu.institution.application.PolicyDocumentService;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Day 14 test fixtures — the one new piece of data Day 7-13's fixtures don't already cover: college policy documents for {@code searchCollegePolicy}. */
@Component
public class Day14TestFixtures {

    private final PolicyDocumentService policyDocumentService;

    public Day14TestFixtures(PolicyDocumentService policyDocumentService) {
        this.policyDocumentService = policyDocumentService;
    }

    /** Creates a policy document as the tenant admin. requiredPermissionCode may be null (visible to any authenticated tenant member). */
    public UUID provisionPolicyDocument(Day7TestFixtures.Tenant tenant, String title, String content, String requiredPermissionCode) {
        TestAuthentication.authenticateAs(tenant.adminUserId());
        try {
            var result = (PolicyDocumentService.CreateResult.Success)
                policyDocumentService.create(tenant.tenantId(), title, content, requiredPermissionCode);
            return result.documentId();
        } finally {
            TestAuthentication.clear();
        }
    }
}
