package ai.fabric.it;

import ai.fabric.core.AIEmbeddingService;
import ai.fabric.dto.AIEmbeddingRequest;
import ai.fabric.dto.AISearchRequest;
import ai.fabric.dto.AISearchResponse;
import ai.fabric.service.VectorManagementService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration coverage for TEST-SEARCH-003: Multi-Entity Type Search.
 */
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("dev")
class SearchMultiEntityIntegrationTest {

    private static final String PRODUCT_ENTITY = "searchmulti_product";
    private static final String USER_ENTITY = "searchmulti_user";
    private static final String ARTICLE_ENTITY = "searchmulti_article";

    @Autowired
    private AIEmbeddingService embeddingService;

    @Autowired
    private VectorManagementService vectorManagementService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @AfterEach
    void tearDown() {
        vectorManagementService.clearVectorsByEntityType(PRODUCT_ENTITY);
        vectorManagementService.clearVectorsByEntityType(USER_ENTITY);
        vectorManagementService.clearVectorsByEntityType(ARTICLE_ENTITY);
    }

    @Test
    @DisplayName("Semantic search surfaces mixed entity types when querying across catalog, users, and content")
    void searchAcrossMultipleEntityTypes() {
        seedProductCatalog();
        seedUserProfiles();
        seedEditorialArticles();

        List<Double> queryEmbedding = embeddingService.generateEmbedding(
            AIEmbeddingRequest.builder()
                .text("luxury travel club membership with exclusive lounges and concierge")
                .build()
        ).getEmbedding();

        AISearchRequest request = AISearchRequest.builder()
            .query("luxury travel club membership")
            .limit(9)
            .threshold(0.25)
            .build();

        AISearchResponse response = vectorManagementService.search(queryEmbedding, request);

        assertNotNull(response, "Search response must not be null");
        assertFalse(response.getResults().isEmpty(), "Search results should not be empty");
        assertTrue(response.getResults().size() <= 9, "Result count should respect limit parameter");

        Set<String> entityTypes = response.getResults().stream()
            .map(result -> (String) result.get("entityType"))
            .collect(Collectors.toSet());

        assertTrue(entityTypes.contains(PRODUCT_ENTITY), "Results should include product entities");
        assertTrue(entityTypes.contains(USER_ENTITY), "Results should include user profile entities");
        assertTrue(entityTypes.contains(ARTICLE_ENTITY), "Results should include article entities");

    }

    private void seedProductCatalog() {
        storeVector(PRODUCT_ENTITY, "membership-hyperion",
            "Hyperion travel membership with exclusive lounges, private advisors, and bespoke itineraries",
            Map.of("category", "membership", "summary", "Flagship travel membership"));
        storeVector(PRODUCT_ENTITY, "membership-aurelius",
            "Aurelius elite travel club unlocking curated city guides and invitation-only events",
            Map.of("category", "membership", "summary", "Elite club product"));
        storeVector(PRODUCT_ENTITY, "gift-card",
            "General gift card for retail products and accessories",
            Map.of("category", "gift", "summary", "Non-travel accessory"));
    }

    private void seedUserProfiles() {
        storeVector(USER_ENTITY, "user-lucia",
            "Lucia is a frequent traveler seeking mentors for luxury travel experiences across Europe",
            Map.of("tier", "platinum", "summary", "Luxury traveler profile"));
        storeVector(USER_ENTITY, "user-daniel",
            "Daniel focuses on budget-friendly backpacking trips and hostel reviews",
            Map.of("tier", "silver", "summary", "Budget traveler"));
    }

    private void seedEditorialArticles() {
        storeVector(ARTICLE_ENTITY, "article-lounges",
            "Editorial: the evolution of private airport lounges and concierge-led travel services",
            Map.of("topic", "travel", "summary", "Concierge lounge article"));
        storeVector(ARTICLE_ENTITY, "article-backpacking",
            "Guide: essentials for extended backpacking through South America",
            Map.of("topic", "adventure", "summary", "Backpacking article"));
    }

    private void storeVector(String entityType, String entityId, String content, Map<String, Object> metadata) {
        List<Double> embedding = embeddingService.generateEmbedding(
            AIEmbeddingRequest.builder().text(content).build()).getEmbedding();
        vectorManagementService.storeVector(entityType, entityId, content, embedding, metadata);
    }

    private Map<String, Object> parseMetadata(Object metadataRaw) {
        if (metadataRaw instanceof String json) {
            try {
                return objectMapper.readValue(json, new TypeReference<>() {});
            } catch (Exception ignored) {
                return Map.of("raw", json);
            }
        }
        if (metadataRaw instanceof Map<?, ?> map) {
            return map.entrySet().stream().collect(Collectors.toMap(
                entry -> String.valueOf(entry.getKey()), Map.Entry::getValue));
        }
        return Map.of();
    }
}
