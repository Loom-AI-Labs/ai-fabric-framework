package ai.fabric.it;

import ai.fabric.core.AIEmbeddingService;
import ai.fabric.dto.AIEmbeddingRequest;
import ai.fabric.dto.AISearchRequest;
import ai.fabric.dto.AISearchResponse;
import ai.fabric.service.VectorManagementService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration coverage for TEST-SEARCH-006: Threshold Tuning.
 */
@Disabled("Disabled due to ApplicationContext loading failures - table creation issues")
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("test")
class SearchThresholdTuningIntegrationTest {

    private static final String ENTITY_TYPE = "searchthreshold_product";

    @Autowired
    private AIEmbeddingService embeddingService;

    @Autowired
    private VectorManagementService vectorManagementService;

    @AfterEach
    void tearDown() {
        vectorManagementService.clearVectorsByEntityType(ENTITY_TYPE);
    }

    @Test
    @DisplayName("Adjusting similarity threshold balances precision and recall")
    void tuningThresholdAdjustsResultDensity() {
        seedCatalog();

        String query = "exclusive travel membership with concierge and lounge access";
        List<Double> queryEmbedding = embeddingService.generateEmbedding(
            AIEmbeddingRequest.builder().text(query).build()
        ).getEmbedding();

        AISearchRequest strictRequest = AISearchRequest.builder()
            .query(query)
            .entityType(ENTITY_TYPE)
            .limit(6)
            .threshold(0.45)
            .build();

        AISearchRequest relaxedRequest = AISearchRequest.builder()
            .query(query)
            .entityType(ENTITY_TYPE)
            .limit(6)
            .threshold(0.20)
            .build();

        AISearchResponse strictResponse = vectorManagementService.search(queryEmbedding, strictRequest);
        AISearchResponse relaxedResponse = vectorManagementService.search(queryEmbedding, relaxedRequest);

        assertFalse(strictResponse.getResults().isEmpty(), "High threshold should still return strong matches");
        assertTrue(relaxedResponse.getResults().size() >= strictResponse.getResults().size(),
            "Relaxed threshold should retrieve at least as many results as strict threshold");

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
    }

    private void seedCatalog() {
        storeVector("hyperion_club",
            "Hyperion Club membership provides concierge travel planning, private lounges, and elite rewards",
            Map.of("tier", "flagship"));
        storeVector("aurelius_concierge",
            "Aurelius concierge service includes itinerary curation, airport transfers, and VIP events",
            Map.of("tier", "elite"));
        storeVector("wander_card",
            "Wander gift card redeemable for flights, hotels, and experiences",
            Map.of("tier", "gift"));
        storeVector("gear_bundle",
            "Adventure gear bundle featuring backpacks, hiking poles, and hydration kits",
            Map.of("tier", "outdoor"));
        storeVector("culinary_pass",
            "Culinary pass featuring restaurant tastings and cooking workshops",
            Map.of("tier", "culinary"));
    }

    private void storeVector(String entityId, String content, Map<String, Object> metadata) {
        List<Double> embedding = embeddingService.generateEmbedding(
            AIEmbeddingRequest.builder().text(content).build()).getEmbedding();
        vectorManagementService.storeVector(ENTITY_TYPE, entityId, content, embedding, metadata);
    }
}
