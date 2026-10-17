package com.setu.institution.application;

import com.setu.identity.TestAuthentication;
import com.setu.shared.ai.TextEmbedder;
import com.setu.testsupport.Day14TestFixtures;
import com.setu.testsupport.Day7TestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real Gemini embeddings (not mocked): a question that shares no words with the policy still finds it, and an unrelated one finds
 * nothing. Tagged {@code live-gemini}, so it is excluded from a plain {@code mvn test} (it spends API quota); run it on purpose with
 * {@code ./mvnw test -DexcludedGroups=none -Dtest=GeminiPolicySearchLiveTests} and a real {@code GEMINI_API_KEY}.
 */
@Tag("live-gemini")
@SpringBootTest(properties = {"spring.ai.model.chat=google-genai", "app.ai.embedding.provider=gemini"})
class GeminiPolicySearchLiveTests {

    @Autowired
    Day7TestFixtures fixtures;
    @Autowired
    Day14TestFixtures day14Fixtures;
    @Autowired
    PolicyDocumentService policyDocumentService;
    @Autowired
    TextEmbedder embedder;

    @AfterEach
    void clearSecurityContext() {
        TestAuthentication.clear();
    }

    @Test
    void aQuestionWithNoSharedWordsFindsThePolicyByMeaning() {
        assertThat(embedder.modelId()).startsWith("gemini:");
        var tenant = fixtures.provisionTenant("GEMP");
        day14Fixtures.provisionPolicyDocument(tenant, "Attendance Policy",
            "Students must maintain at least 75% attendance in every enrolled section to remain eligible to sit an end-of-term assessment.", null);
        day14Fixtures.provisionPolicyDocument(tenant, "Grading Policy",
            "Grades are published per assessment component. A later correction supersedes the prior grade but never overwrites it.", null);

        TestAuthentication.authenticateAs(tenant.adminUserId());
        var byMeaning = policyDocumentService.search("how many classes can I miss");
        var unrelated = policyDocumentService.search("what is the capital of France");

        assertThat(byMeaning).isNotEmpty();
        assertThat(byMeaning.get(0).title()).isEqualTo("Attendance Policy");
        assertThat(unrelated).isEmpty();
    }
}
