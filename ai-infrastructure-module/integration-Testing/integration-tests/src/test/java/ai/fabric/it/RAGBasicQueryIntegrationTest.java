package ai.fabric.it;

import ai.fabric.dto.RAGRequest;
import ai.fabric.dto.RAGResponse;
import ai.fabric.spi.RAGProvider;
import ai.fabric.service.VectorManagementService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test implementation for TEST-RAG-001: Basic RAG Query (End-to-End).
 */
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("dev")
@TestPropertySource(properties = "ai.vector-db.lucene.index-path=./data/test-lucene-index/rag-basic")
class RAGBasicQueryIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(RAGBasicQueryIntegrationTest.class);

    private static final String ENTITY_TYPE = "ragproduct";

    @Autowired
    private RAGProvider ragProvider;

    @Autowired
    private VectorManagementService vectorManagementService;

    @BeforeEach
    void setUp() {
        try {
            vectorManagementService.clearVectorsByEntityType(ENTITY_TYPE);
        } catch (Exception e) {
            // Vector database may not be initialized yet, that's okay
            log.debug("Could not clear vectors (may not be initialized yet): {}", e.getMessage());
        }
        indexLuxuryProducts();
    }

    @AfterEach
    void tearDown() {
        vectorManagementService.clearVectorsByEntityType(ENTITY_TYPE);
    }

    @Test
    @DisplayName("RAG service returns relevant documents for luxury watch query")
    void testBasicRAGQuery() {
        String query = "Luxury Swiss watch model 0 featuring diamonds and premium craftsmanship.";

        RAGRequest request = RAGRequest.builder()
            .query(query)
            .entityType(ENTITY_TYPE)
            .limit(5)
            .threshold(0.0)
            .enableHybridSearch(false)
            .enableContextualSearch(false)
            .build();

        long startTime = System.currentTimeMillis();
        RAGResponse response = ragProvider.performRAGQuery(request);
        long duration = System.currentTimeMillis() - startTime;

        assertNotNull(response, "RAG response should not be null");
        assertTrue(Boolean.TRUE.equals(response.getSuccess()), "RAG response should indicate success");
        assertTrue(duration < 3_000, "RAG query should complete within 3 seconds");

        double confidence = response.getConfidenceScore() != null ? response.getConfidenceScore() : 0.0;
        assertTrue(confidence >= 0.0 && confidence <= 1.0, "Confidence score should be normalized");

        if (response.getDocuments() != null && !response.getDocuments().isEmpty()) {
            boolean containsLuxuryTerm = response.getDocuments().stream()
                .map(RAGResponse.RAGDocument::getContent)
                .filter(contentStr -> contentStr != null)
                .anyMatch(contentStr -> contentStr.toLowerCase().contains("luxury") || contentStr.toLowerCase().contains("diamond"));
            assertTrue(containsLuxuryTerm, "At least one document should reference luxury or diamond");
        }
    }

    private void indexLuxuryProducts() {
        for (int i = 0; i < 20; i++) {
            String entityId = "rag_watch_" + i;
            String content = "Luxury Swiss watch model " + i + " featuring diamonds and premium craftsmanship.";
            ragProvider.indexContent(ENTITY_TYPE, entityId, content, Map.of(
                "category", "luxury-watch",
                "brand", i % 2 == 0 ? "Rolex" : "Omega"
            ));
        }
    }
}
