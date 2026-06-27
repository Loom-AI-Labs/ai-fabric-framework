package ai.fabric.it;

import ai.fabric.dto.AISearchRequest;
import ai.fabric.dto.AISearchResponse;
import ai.fabric.service.VectorManagementService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test implementation for TEST-VECTOR-002: k-NN Search with HNSW (Lucene).
 */
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "ai.vector-db.lucene.index-path=./data/test-lucene-index/knn",
    "ai.vector-db.lucene.similarity-threshold=0.0",
    "ai.vector-db.lucene.max-results=100"
})
class LuceneKNNSearchIntegrationTest {

    private static final String ENTITY_TYPE = "test-knn-product";
    private static final String NOISE_ENTITY_TYPE = "test-knn-noise";
    private static final int VECTOR_COUNT = 100;
    private static final double SIMILARITY_THRESHOLD = 0.0; // allow full result set for validation
    private static final List<Double> QUERY_VECTOR = List.of(1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0);

    @Autowired
    private VectorManagementService vectorManagementService;

    @BeforeEach
    void setUp() {
        vectorManagementService.clearVectorsByEntityType(ENTITY_TYPE);
        vectorManagementService.clearVectorsByEntityType(NOISE_ENTITY_TYPE);
    }

    @AfterEach
    void tearDown() {
        vectorManagementService.clearVectorsByEntityType(ENTITY_TYPE);
        vectorManagementService.clearVectorsByEntityType(NOISE_ENTITY_TYPE);
    }

    @Test
    @DisplayName("Lucene k-NN search returns scoped, sorted top-k results")
    void testLuceneKNNSearchWithHNSW() {
        for (int i = 0; i < VECTOR_COUNT; i++) {
            vectorManagementService.storeVector(
                ENTITY_TYPE,
                entityId(i),
                "Deterministic luxury watch catalog item " + i,
                vectorForRank(i),
                Map.of("category", "category-" + (i % 20))
            );
        }

        seedOutOfScopeNoise();

        int[] limits = new int[]{5, 10, 50, 100};
        for (int limit : limits) {
            AISearchRequest searchRequest = AISearchRequest.builder()
                .query("luxury Swiss watch with diamonds")
                .entityType(ENTITY_TYPE)
                .limit(limit)
                .threshold(SIMILARITY_THRESHOLD)
                .build();

            AISearchResponse response = vectorManagementService.search(QUERY_VECTOR, searchRequest);

            assertNotNull(response, "Search response must not be null");
            assertNotNull(response.getResults(), "Search results list must not be null");
            assertEquals(limit, response.getResults().size(), "Search must return exactly k results");

            List<Double> similarities = response.getResults().stream()
                .map(result -> (Double) result.get("similarity"))
                .collect(Collectors.toList());

            assertFalse(similarities.isEmpty(), "Similarity scores must be present");
            assertTrue(similarities.stream().allMatch(score -> score >= 0.0 && score <= 1.0),
                "All similarity scores should be normalized between 0.0 and 1.0");

            for (int i = 0; i < similarities.size() - 1; i++) {
                assertTrue(similarities.get(i) >= similarities.get(i + 1),
                    "Results should be sorted in descending order of similarity");
            }

            response.getResults().forEach(result ->
                assertEquals(ENTITY_TYPE, result.get("entityType"), "Each result should belong to the expected entity type")
            );

            List<String> resultIds = response.getResults().stream()
                .map(result -> (String) result.get("id"))
                .toList();
            assertFalse(resultIds.stream().anyMatch(id -> id.startsWith("noise-")),
                "Entity-type filtering should exclude out-of-scope vectors");

            if (limit == 5) {
                assertEquals(List.of(entityId(0), entityId(1), entityId(2), entityId(3), entityId(4)), resultIds,
                    "The closest deterministic vectors should occupy the top five positions");
            }
        }
    }

    private void seedOutOfScopeNoise() {
        for (int i = 0; i < 3; i++) {
            vectorManagementService.storeVector(
                NOISE_ENTITY_TYPE,
                "noise-" + i,
                "Out-of-scope vector with very high query similarity " + i,
                vectorForRank(i),
                Map.of("category", "noise")
            );
        }
    }

    private String entityId(int rank) {
        return "product-" + String.format("%03d", rank);
    }

    private List<Double> vectorForRank(int rank) {
        double x = 1.0d - (rank * 0.004d);
        double y = Math.sqrt(Math.max(0.0d, 1.0d - (x * x)));
        return List.of(x, y, 0.0d, 0.0d, 0.0d, 0.0d, 0.0d, 0.0d);
    }
}
