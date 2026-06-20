package ai.fabric.it;

import ai.fabric.dto.AISearchRequest;
import ai.fabric.dto.AISearchResponse;
import ai.fabric.service.VectorManagementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration coverage for TEST-SEARCH-006: Threshold Tuning.
 */
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "ai.vector-db.lucene.index-path=./data/test-lucene-index/search-threshold",
    "ai.vector-db.lucene.similarity-threshold=0.0",
    "ai.vector-db.lucene.max-results=10"
})
class SearchThresholdTuningIntegrationTest {

    private static final String ENTITY_TYPE = "searchthreshold_product";
    private static final List<Double> QUERY_VECTOR = List.of(1.0, 0.0, 0.0, 0.0);

    @Autowired
    private VectorManagementService vectorManagementService;

    @BeforeEach
    void setUp() {
        vectorManagementService.clearVectorsByEntityType(ENTITY_TYPE);
    }

    @Test
    @DisplayName("Adjusting similarity threshold balances precision and recall")
    void tuningThresholdAdjustsResultDensity() {
        seedCatalog();

        AISearchRequest strictRequest = AISearchRequest.builder()
            .query("exclusive travel membership with concierge and lounge access")
            .entityType(ENTITY_TYPE)
            .limit(6)
            .threshold(0.85)
            .build();

        AISearchRequest relaxedRequest = AISearchRequest.builder()
            .query("exclusive travel membership with concierge and lounge access")
            .entityType(ENTITY_TYPE)
            .limit(6)
            .threshold(0.0)
            .build();

        AISearchResponse strictResponse = vectorManagementService.search(QUERY_VECTOR, strictRequest);
        AISearchResponse relaxedResponse = vectorManagementService.search(QUERY_VECTOR, relaxedRequest);

        assertFalse(strictResponse.getResults().isEmpty(), "High threshold should still return strong matches");
        assertTrue(relaxedResponse.getResults().size() >= strictResponse.getResults().size(),
            "Relaxed threshold should retrieve at least as many results as strict threshold");
        assertTrue(relaxedResponse.getResults().size() > strictResponse.getResults().size(),
            "Relaxed threshold should admit lower-similarity catalog entries");

        double strictAverage = strictResponse.getResults().stream()
            .mapToDouble(result -> (Double) result.get("similarity"))
            .average()
            .orElse(0.0);
        double relaxedAverage = relaxedResponse.getResults().stream()
            .mapToDouble(result -> (Double) result.get("similarity"))
            .average()
            .orElse(0.0);

        assertTrue(strictAverage >= relaxedAverage,
            "Average similarity should be higher when using a stricter threshold");

        // Ensure high-similarity items are present in both result sets
        List<String> strictIds = strictResponse.getResults().stream()
            .map(result -> (String) result.get("id"))
            .toList();
        strictIds.forEach(id -> assertTrue(relaxedResponse.getResults().stream()
            .map(result -> (String) result.get("id"))
            .toList()
            .contains(id), "Relaxed results should retain strong matches"));

        relaxedResponse.getResults().forEach(result ->
            assertEquals(ENTITY_TYPE, result.get("entityType"), "Search should remain scoped to the entity type")
        );
    }

    private void seedCatalog() {
        storeVector("hyperion_club",
            "Hyperion Club membership provides concierge travel planning, private lounges, and elite rewards",
            List.of(1.0, 0.0, 0.0, 0.0),
            Map.of("tier", "flagship"));
        storeVector("aurelius_concierge",
            "Aurelius concierge service includes itinerary curation, airport transfers, and VIP events",
            List.of(0.96, 0.04, 0.0, 0.0),
            Map.of("tier", "elite"));
        storeVector("wander_card",
            "Wander gift card redeemable for flights, hotels, and experiences",
            List.of(0.35, 0.65, 0.0, 0.0),
            Map.of("tier", "gift"));
        storeVector("gear_bundle",
            "Adventure gear bundle featuring backpacks, hiking poles, and hydration kits",
            List.of(0.0, 1.0, 0.0, 0.0),
            Map.of("tier", "outdoor"));
        storeVector("culinary_pass",
            "Culinary pass featuring restaurant tastings and cooking workshops",
            List.of(0.0, 0.0, 1.0, 0.0),
            Map.of("tier", "culinary"));
    }

    private void storeVector(String entityId, String content, List<Double> embedding, Map<String, Object> metadata) {
        vectorManagementService.storeVector(ENTITY_TYPE, entityId, content, embedding, metadata);
    }
}
