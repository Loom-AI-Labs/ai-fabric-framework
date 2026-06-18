package ai.fabric.it;

import ai.fabric.core.AIEmbeddingService;
import ai.fabric.dto.AIEmbeddingRequest;
import ai.fabric.dto.AIEmbeddingResponse;
import ai.fabric.dto.AISearchRequest;
import ai.fabric.dto.AISearchResponse;
import ai.fabric.dto.VectorRecord;
import ai.fabric.vector.lucene.LuceneVectorDatabaseService;
import ai.fabric.rag.VectorDatabaseService;
import ai.fabric.service.VectorManagementService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test implementation for TEST-VECTOR-004: Index Persistence and Recovery.
 */
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("dev")
@TestPropertySource(properties = {
    "ai.vector-db.lucene.index-path=./data/test-lucene-index/persistence",
    "ai.vector-db.lucene.cleanup-on-close=false"
})
class LuceneIndexPersistenceIntegrationTest {

    private static final String ENTITY_TYPE = "persistvector";
    private static final int VECTOR_COUNT = 100;

    @Autowired
    private AIEmbeddingService embeddingService;

    @Autowired
    private VectorManagementService vectorManagementService;

    @Autowired
    private VectorDatabaseService vectorDatabaseService;

    @BeforeEach
    void setUp() {
        vectorManagementService.clearVectorsByEntityType(ENTITY_TYPE);
    }

    @AfterEach
    void tearDown() {
        vectorManagementService.clearVectorsByEntityType(ENTITY_TYPE);
    }

    @Test
    @DisplayName("Lucene index persists vectors across service restart")
    void testIndexPersistenceAndRecovery() {
        List<String> storedEntityIds = new ArrayList<>(VECTOR_COUNT);

        for (int i = 0; i < VECTOR_COUNT; i++) {
            String entityId = "persist_vector_" + i;
            String content = "Persistence test product " + i + " description " + System.nanoTime();
            AIEmbeddingResponse embedding = embeddingService.generateEmbedding(
                AIEmbeddingRequest.builder().text(content).build()
            );

            vectorManagementService.storeVector(
                ENTITY_TYPE,
                entityId,
                content,
                embedding.getEmbedding(),
                java.util.Map.of("batch", "persistence")
            );
            storedEntityIds.add(entityId);
        }

        List<VectorRecord> beforeRestart = vectorManagementService.getVectorsByEntityType(ENTITY_TYPE);
        assertEquals(VECTOR_COUNT, beforeRestart.size(), "All vectors should be present before restart");

        Set<String> beforeIds = new HashSet<>();
        beforeRestart.forEach(record -> beforeIds.add(record.getEntityId()));

        AISearchResponse preRestartSearch = vectorManagementService.search(
            embeddingService.generateEmbedding(
                AIEmbeddingRequest.builder().text("luxury accessories with diamonds").build()
            ).getEmbedding(),
            AISearchRequest.builder()
                .query("luxury accessories with diamonds")
                .entityType(ENTITY_TYPE)
                .limit(5)
                .threshold(0.0)
                .build()
        );
        assertNotNull(preRestartSearch, "Pre-restart search response should not be null");
        assertFalse(preRestartSearch.getResults().isEmpty(), "Pre-restart search should return results");

        // Restart the Lucene service
        restartLuceneService();

        List<VectorRecord> afterRestart = vectorManagementService.getVectorsByEntityType(ENTITY_TYPE);
        assertEquals(VECTOR_COUNT, afterRestart.size(), "All vectors should be recovered after restart");

        Set<String> afterIds = new HashSet<>();
        afterRestart.forEach(record -> afterIds.add(record.getEntityId()));
        assertEquals(beforeIds, afterIds, "Entity IDs should remain consistent after restart");

        AISearchResponse postRestartSearch = vectorManagementService.search(
            embeddingService.generateEmbedding(
                AIEmbeddingRequest.builder().text("luxury accessories with diamonds").build()
            ).getEmbedding(),
            AISearchRequest.builder()
                .query("luxury accessories with diamonds")
                .entityType(ENTITY_TYPE)
                .limit(5)
                .threshold(0.0)
                .build()
        );

        assertNotNull(postRestartSearch, "Post-restart search response should not be null");
        assertEquals(preRestartSearch.getResults().size(), postRestartSearch.getResults().size(),
            "Search result count should remain consistent after restart");

        Set<String> beforeSearchIds = extractEntityIds(preRestartSearch);
        Set<String> afterSearchIds = extractEntityIds(postRestartSearch);
        assertEquals(beforeSearchIds, afterSearchIds, "Search results should be identical after restart");
    }

    private void restartLuceneService() {
        VectorDatabaseService current = vectorDatabaseService;
        // Unwrap common decorators (e.g., governance) if present.
        while (true) {
            try {
                java.lang.reflect.Field delegateField = current.getClass().getDeclaredField("delegate");
                delegateField.setAccessible(true);
                Object delegate = delegateField.get(current);
                if (delegate instanceof VectorDatabaseService next) {
                    current = next;
                    continue;
                }
                break;
            } catch (Exception e) {
                break;
            }
        }

        if (current instanceof LuceneVectorDatabaseService luceneService) {
            luceneService.cleanup();
            luceneService.initialize();
        } else {
            throw new IllegalStateException("Underlying vector service is not Lucene: " + current.getClass().getName());
        }
    }

    private Set<String> extractEntityIds(AISearchResponse response) {
        Set<String> ids = new HashSet<>();
        response.getResults().forEach(result -> ids.add((String) result.get("id")));
        return ids;
    }
}
