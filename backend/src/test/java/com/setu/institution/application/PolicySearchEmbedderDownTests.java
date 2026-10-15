package com.setu.institution.application;

import com.setu.identity.TestAuthentication;
import com.setu.shared.ai.TextEmbedder;
import com.setu.testsupport.Day14TestFixtures;
import com.setu.testsupport.Day7TestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * An embedding provider that is down must never stop a college creating or searching its policies: the passage is stored without a
 * vector, full-text search finds it at once, and a later indexing pass adds the vector.
 */
@SpringBootTest
class PolicySearchEmbedderDownTests {

    @MockitoBean
    TextEmbedder embedder;

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

    @Test
    void whileTheEmbedderIsDownDocumentsAreStillCreatedAndFoundByText_andGetTheirVectorLater() {
        when(embedder.modelId()).thenReturn("test-model");
        when(embedder.minSimilarity()).thenReturn(0.1);
        when(embedder.embed(anyString())).thenReturn(Optional.empty());
        var tenant = fixtures.provisionTenant("RAGD");
        UUID docId = day14Fixtures.provisionPolicyDocument(tenant, "Exam Policy", "Students must carry identity cards into every examination hall.", null);

        assertThat(jdbc.sql("SELECT count(*) FROM institution.policy_chunks WHERE document_id = :d AND embedding IS NULL AND embedding_model IS NULL")
            .param("d", docId).query(Integer.class).single()).as("stored without a vector").isEqualTo(1);
        TestAuthentication.authenticateAs(tenant.adminUserId());
        assertThat(policyDocumentService.search("identity cards examination")).as("found by full-text alone").isNotEmpty();
        TestAuthentication.clear();

        float[] vector = new float[TextEmbedder.DIMENSIONS];
        vector[0] = 1f;
        when(embedder.embed(anyString())).thenReturn(Optional.of(vector));
        // The shared test database holds many chunks from other tests that also need the new model; a pass handles a bounded number.
        for (int pass = 0; pass < 100 && vectorCount(docId) == 0; pass++) {
            indexer.indexPending();
        }

        assertThat(vectorCount(docId)).as("the vector arrives on a later pass").isEqualTo(1);
    }

    private int vectorCount(UUID docId) {
        return jdbc.sql("SELECT count(*) FROM institution.policy_chunks WHERE document_id = :d AND embedding IS NOT NULL AND embedding_model = 'test-model'")
            .param("d", docId).query(Integer.class).single();
    }
}
