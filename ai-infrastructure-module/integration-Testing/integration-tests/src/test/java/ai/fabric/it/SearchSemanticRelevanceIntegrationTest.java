package ai.fabric.it;

import ai.fabric.core.AIEmbeddingService;
import ai.fabric.dto.AIEmbeddingRequest;
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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test for TEST-SEARCH-001: Semantic Search Relevance.
 */
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("dev")
@TestPropertySource(properties = "ai.vector-db.lucene.index-path=./data/test-lucene-index/search-semantic-relevance")
class SearchSemanticRelevanceIntegrationTest {

    private static final String ENTITY_TYPE = "searchsemanticproduct";

    @Autowired
    private VectorManagementService vectorManagementService;

    @Autowired
    private AIEmbeddingService embeddingService;

    @BeforeEach
    void setUp() {
        vectorManagementService.clearVectorsByEntityType(ENTITY_TYPE);
    }

    @AfterEach
    void tearDown() {
        vectorManagementService.clearVectorsByEntityType(ENTITY_TYPE);
    }

    @Test
    @DisplayName("Semantic search prioritises expected products and achieves strong relevance metrics")
    void semanticSearchProducesRelevantResults() {
        buildScenarios().forEach(scenario -> {
            vectorManagementService.clearVectorsByEntityType(ENTITY_TYPE);
            seedDataset(scenario.products());

            List<Double> queryEmbedding = embeddingService.generateEmbedding(
                AIEmbeddingRequest.builder().text(scenario.query()).build()
            ).getEmbedding();

            AISearchRequest request = AISearchRequest.builder()
                .query(scenario.query())
                .entityType(ENTITY_TYPE)
                .limit(5)
                .threshold(0.0)
                .build();

            AISearchResponse response = vectorManagementService.searchByEntityType(
                queryEmbedding,
                ENTITY_TYPE,
                request.getLimit(),
                request.getThreshold()
            );

            assertNotNull(response, "Search response should not be null");
            assertFalse(response.getResults().isEmpty(), "Search should return results");

            List<String> resultIds = response.getResults().stream()
                .map(result -> Objects.toString(result.get("id")))
                .collect(Collectors.toList());

            assertPrecisionAndRelevance(scenario.query(), scenario.expectedIds(), resultIds);
            assertNdcgAboveThreshold(scenario.expectedIds(), resultIds, 0.5);
        });
    }

    private void assertPrecisionAndRelevance(String query, List<String> expectedIds, List<String> resultIds) {
        assertFalse(resultIds.isEmpty(), () -> "No results returned for query: " + query);

        List<String> topThree = resultIds.subList(0, Math.min(3, resultIds.size()));
        long hitsInTopThree = expectedIds.stream().filter(topThree::contains).count();
        assertTrue(hitsInTopThree >= 1,
            () -> "Expected at least one relevant result within top three for query " + query +
                ", but got " + topThree);

        long hitsInTopFive = expectedIds.stream().filter(resultIds::contains).count();
        double precisionAtFive = hitsInTopFive / Math.min(5.0, resultIds.size());
        assertTrue(hitsInTopFive >= 2,
            () -> "Expected at least two relevant results within top five for query " + query +
                ", but got " + resultIds);
        assertTrue(precisionAtFive >= 0.4,
            () -> "Precision@5 should be at least 0.4 for query " + query +
                ", but was " + precisionAtFive + " with results " + resultIds);
    }

    private void assertNdcgAboveThreshold(List<String> expectedIds, List<String> resultIds, double threshold) {
        double dcg = 0.0;
        for (int i = 0; i < resultIds.size(); i++) {
            String resultId = resultIds.get(i);
            if (expectedIds.contains(resultId)) {
                dcg += 1.0 / (Math.log(i + 2) / Math.log(2));
            }
        }

        int idealCount = Math.min(expectedIds.size(), resultIds.size());
        double idcg = 0.0;
        for (int i = 0; i < idealCount; i++) {
            idcg += 1.0 / (Math.log(i + 2) / Math.log(2));
        }

        double ndcg = idcg == 0.0 ? 0.0 : dcg / idcg;
        assertTrue(ndcg >= threshold,
            () -> "NDCG should be at least " + threshold + " but was " + ndcg +
                " for result order " + resultIds);
    }

    private void seedDataset(Map<String, String> products) {
        List<Map.Entry<String, String>> entries = new ArrayList<>(products.entrySet());
        entries.forEach(entry -> {
            List<Double> embedding = embeddingService.generateEmbedding(
                AIEmbeddingRequest.builder().text(entry.getValue()).build()
            ).getEmbedding();

            Map<String, Object> metadata = Map.of(
                "name", entry.getKey(),
                "category", deriveCategory(entry.getKey())
            );

            String vectorId = vectorManagementService.storeVector(
                ENTITY_TYPE,
                entry.getKey(),
                entry.getValue(),
                embedding,
                metadata
            );

            assertNotNull(vectorId, () -> "Vector ID should not be null for entity " + entry.getKey());
        });
    }

    private List<SearchScenario> buildScenarios() {
        Map<String, String> timepieces = new LinkedHashMap<>();
        timepieces.put("rolex_submariner",
            "Rolex Submariner Swiss luxury timepiece dive watch with oystersteel bracelet and ceramic bezel.");
        timepieces.put("patek_grand_complications",
            "Patek Philippe Swiss haute horology timepiece with perpetual calendar and hand-finished movement.");
        timepieces.put("gucci_dionysus",
            "Gucci Dionysus leather accessory handbag with feline closure and evening styling for luxury events.");

        Map<String, String> accessories = new LinkedHashMap<>();
        accessories.put("hermes_birkin",
            "Hermes Birkin elegant leather accessory for women crafted from togo leather, palladium hardware and meticulous saddle stitching.");
        accessories.put("gucci_dionysus",
            "Gucci Dionysus luxury leather handbag with suede lining, antique silver tiger head and evening accessory styling.");
        accessories.put("louis_vuitton_capucines",
            "Louis Vuitton Capucines refined leather bag featuring structured silhouette, satin lining and feminine accessory design.");
        accessories.put("rolex_submariner",
            "Rolex Submariner dive watch displayed as a contrasting product within accessory collection.");

        Map<String, String> chronographs = new LinkedHashMap<>();
        chronographs.put("omega_speedmaster",
            "Omega Speedmaster high-performance chronograph racing watch designed for astronauts with tachymeter scale and rally heritage.");
        chronographs.put("tag_heuer_monaco",
            "TAG Heuer Monaco motorsport chronograph racing watch featuring gulf livery, square case, and aggressive high-performance movement.");
        chronographs.put("breitling_navitimer",
            "Breitling Navitimer aviation chronograph racing watch with slide rule bezel, high-performance mechanics, and pilot heritage.");
        chronographs.put("chronograph_racing_prototype",
            "High-performance chronograph racing watch prototype with carbon fiber case, telemetry scale, and endurance racing tuning.");
        chronographs.put("gucci_dionysus",
            "Gucci Dionysus evening accessory cross-listed for contrast against chronograph racing watches.");

        return List.of(
            new SearchScenario(
                "luxury Swiss timepiece",
                List.of("rolex_submariner", "patek_grand_complications"),
                timepieces
            ),
            new SearchScenario(
                "elegant leather accessory for women",
                List.of("hermes_birkin", "gucci_dionysus", "louis_vuitton_capucines"),
                accessories
            ),
            new SearchScenario(
                "high-performance chronograph racing watch",
                List.of("omega_speedmaster", "tag_heuer_monaco", "breitling_navitimer", "chronograph_racing_prototype"),
                chronographs
            )
        );
    }

    private record SearchScenario(String query, List<String> expectedIds, Map<String, String> products) {
    }

    private String deriveCategory(String productId) {
        if (productId.contains("birkin") || productId.contains("gucci") || productId.contains("louis_vuitton")) {
            return "accessory";
        }
        if (productId.contains("fenix") || productId.contains("watch_ultra")) {
            return "wearable";
        }
        return "timepiece";
    }
}
