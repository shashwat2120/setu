package com.setu.institution.application;

import com.setu.aiexplain.infrastructure.embedding.HashingTextEmbedder;
import com.setu.identity.TestAuthentication;
import com.setu.identity.domain.RoleCode;
import com.setu.testsupport.Day14TestFixtures;
import com.setu.testsupport.Day7TestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Retrieval over college policy documents with pgvector (canonical spec §10): documents are chunked and embedded when created, search
 * is hybrid (vector plus full-text), and the tenant and document-ACL checks are applied inside the query, before ranking.
 */
@SpringBootTest
class PolicySearchRetrievalTests {

    @Autowired
    Day7TestFixtures fixtures;
    @Autowired
    Day14TestFixtures day14Fixtures;
    @Autowired
    PolicyDocumentService policyDocumentService;
    @Autowired
    PolicyIndexer indexer;
    @Autowired
    JdbcClient jdbc;

    @AfterEach
    void clearSecurityContext() {
        TestAuthentication.clear();
    }

    private List<PolicyDocumentService.PolicySearchResult> searchAs(UUID userId, String query) {
        TestAuthentication.authenticateAs(userId);
        try {
            return policyDocumentService.search(query);
        } finally {
            TestAuthentication.clear();
        }
    }

    @Test
    void aCreatedDocumentIsChunkedAndEmbeddedWithTheCurrentModel() {
        var tenant = fixtures.provisionTenant("RAG1");
        UUID docId = day14Fixtures.provisionPolicyDocument(tenant, "Attendance Policy",
            "Students must maintain at least 75% attendance in every enrolled section.\n\nBelow that threshold a documented exception is required.", null);

        var rows = jdbc.sql("SELECT chunk_index, embedding IS NOT NULL AS embedded, embedding_model FROM institution.policy_chunks WHERE document_id = :d ORDER BY chunk_index")
            .param("d", docId).query((rs, i) -> rs.getBoolean("embedded") + ":" + rs.getString("embedding_model")).list();

        assertThat(rows).containsExactly("true:" + HashingTextEmbedder.MODEL_ID, "true:" + HashingTextEmbedder.MODEL_ID);
    }

    @Test
    void aQuestionInDifferentWordsFindsTheRelevantPassageAndAnUnrelatedOneFindsNothing() {
        var tenant = fixtures.provisionTenant("RAG2");
        day14Fixtures.provisionPolicyDocument(tenant, "Attendance Policy",
            "Students must maintain at least 75% attendance in every enrolled section to remain eligible to sit an assessment.", null);
        day14Fixtures.provisionPolicyDocument(tenant, "Grading Policy",
            "Grades are published per assessment component. A later correction supersedes the prior grade but never overwrites it.", null);

        var attendance = searchAs(tenant.adminUserId(), "what attendance is required for enrolled sections");
        var grading = searchAs(tenant.adminUserId(), "can a published grade be corrected");
        var unrelated = searchAs(tenant.adminUserId(), "parking permits for bicycles near the library");

        assertThat(attendance).isNotEmpty();
        assertThat(attendance.get(0).title()).isEqualTo("Attendance Policy");
        assertThat(grading).isNotEmpty();
        assertThat(grading.get(0).title()).isEqualTo("Grading Policy");
        assertThat(unrelated).as("no match means no answer, not the least-bad passage").isEmpty();
    }

    @Test
    void theAccessCheckHappensBeforeRankingSoRestrictedPassagesCannotCrowdOutAllowedOnes() {
        var tenant = fixtures.provisionTenant("RAG3");
        UUID studentUserId = fixtures.provisionUserWithRole(tenant.tenantId(), RoleCode.STUDENT, "RAG3S");
        // Six staff-only documents that match the question far better than the one public document. If access were checked after
        // taking the best matches, they would fill the candidate list and the student would get nothing at all.
        for (int i = 1; i <= 6; i++) {
            day14Fixtures.provisionPolicyDocument(tenant, "Staff leave circular " + i,
                "Faculty leave scheduling: staff leave scheduling rules for leave requests and leave approval, leave scheduling deadlines.",
                "VIEW_TENANT_DATA");
        }
        day14Fixtures.provisionPolicyDocument(tenant, "Student leave of absence",
            "A student may request a leave of absence through the registrar.", null);

        var asStudent = searchAs(studentUserId, "leave scheduling");
        var asAdmin = searchAs(tenant.adminUserId(), "leave scheduling");

        assertThat(asStudent).extracting(PolicyDocumentService.PolicySearchResult::title).containsExactly("Student leave of absence");
        assertThat(asAdmin).extracting(PolicyDocumentService.PolicySearchResult::title).anyMatch(t -> t.startsWith("Staff leave circular"));
    }

    @Test
    void aTenantNeverSeesAnotherTenantsPassagesEvenWhenTheyAreIdentical() {
        var a = fixtures.provisionTenant("RAG4A");
        var b = fixtures.provisionTenant("RAG4B");
        String text = "Hostel curfew is ten in the evening and visitors must register at the front desk.";
        UUID docA = day14Fixtures.provisionPolicyDocument(a, "Hostel Rules", text, null);
        UUID docB = day14Fixtures.provisionPolicyDocument(b, "Hostel Rules", text, null);

        var forA = searchAs(a.adminUserId(), "hostel curfew visitors");
        var forB = searchAs(b.adminUserId(), "hostel curfew visitors");

        assertThat(forA).extracting(PolicyDocumentService.PolicySearchResult::documentId).containsOnly(docA);
        assertThat(forB).extracting(PolicyDocumentService.PolicySearchResult::documentId).containsOnly(docB);
    }

    @Test
    void aChunkEmbeddedByAnotherModelIsReEmbeddedInTheBackground() {
        var tenant = fixtures.provisionTenant("RAG5");
        UUID docId = day14Fixtures.provisionPolicyDocument(tenant, "Fee Policy", "Fees are due before the start of each academic period.", null);
        jdbc.sql("UPDATE institution.policy_chunks SET embedding_model = 'some-older-model' WHERE document_id = :d").param("d", docId).update();

        indexer.indexPending();

        String model = jdbc.sql("SELECT DISTINCT embedding_model FROM institution.policy_chunks WHERE document_id = :d")
            .param("d", docId).query(String.class).single();
        assertThat(model).isEqualTo(HashingTextEmbedder.MODEL_ID);
    }

    @Test
    void aDocumentWithNoChunksYetIsIndexedByTheBackgroundPass() {
        var tenant = fixtures.provisionTenant("RAG6");
        UUID docId = day14Fixtures.provisionPolicyDocument(tenant, "Library Policy", "Books may be borrowed for fourteen days.", null);
        jdbc.sql("DELETE FROM institution.policy_chunks WHERE document_id = :d").param("d", docId).update();

        indexer.indexPending();

        assertThat(jdbc.sql("SELECT count(*) FROM institution.policy_chunks WHERE document_id = :d").param("d", docId).query(Integer.class).single())
            .isEqualTo(1);
        assertThat(searchAs(tenant.adminUserId(), "borrow books")).isNotEmpty();
    }

    @Test
    void aBlankQueryOrACallerWithoutASingleCollegeGetsNothing() {
        var tenant = fixtures.provisionTenant("RAG7");
        day14Fixtures.provisionPolicyDocument(tenant, "Any Policy", "Some policy text about anything at all.", null);

        assertThat(searchAs(tenant.adminUserId(), "  ")).isEmpty();
        assertThat(searchAs(UUID.randomUUID(), "policy")).isEmpty();
    }
}
