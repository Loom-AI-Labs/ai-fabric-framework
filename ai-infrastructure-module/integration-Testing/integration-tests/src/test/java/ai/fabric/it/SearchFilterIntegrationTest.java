package ai.fabric.it;

import ai.fabric.core.AIEmbeddingService;
import ai.fabric.dto.AIEmbeddingRequest;
import ai.fabric.dto.AISearchRequest;
import ai.fabric.dto.AISearchResponse;
import ai.fabric.service.VectorManagementService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("dev")
@TestPropertySource(properties = "ai.vector-db.lucene.index-path=./data/test-lucene-index/search-filter-precision")
class SearchFilterIntegrationTest {

    private static final String ENTITY_TYPE = "searchfilterproduct";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Autowired
    private VectorManagementService vectorManagementService;

    @Autowired
    private AIEmbeddingService embeddingService;

    @BeforeEach
    void setUp() {
        vectorManagementService.clearVectorsByEntityType(ENTITY_TYPE);
        seedCatalog();
    }

    @AfterEach
    void tearDown() {
        vectorManagementService.clearVectorsByEntityType(ENTITY_TYPE);
    }

    @Test
    @DisplayName("Vector search supports metadata-based filtering for price and category")
    void searchWithFiltersReturnsOnlyMatchingProducts() {
        String query = "luxury watch";
        List<Double> queryEmbedding = embeddingService.generateEmbedding(
            AIEmbeddingRequest.builder().text(query).build()
        ).getEmbedding();

        AISearchRequest request = AISearchRequest.builder()
            .query(query)
            .entityType(ENTITY_TYPE)
            .limit(10)
            .threshold(0.0)
            .filters("{\"category\":\"watch\",\"priceMax\":5000,\"inStock\":true}")
            .build();

        AISearchResponse response = vectorManagementService.searchByEntityType(queryEmbedding, ENTITY_TYPE, request.getLimit(), request.getThreshold());

        assertNotNull(response, "Search response should not be null");
        assertFalse(response.getResults().isEmpty(), "Search should produce candidate products");

        List<Map<String, Object>> enrichedResults = response.getResults().stream()
            .map(this::parseMetadata)
            .toList();

        assertTrue(enrichedResults.stream().anyMatch(result -> !Objects.equals(result.get("category"), "watch") || toDouble(result.get("price")) > 5000),
            "Baseline results should contain at least one product outside the desired filter range to prove filter effectiveness");

        Map<String, Object> expectedFilters = Map.of(
            "category", "watch",
            "priceMax", 5000.0,
            "inStock", true
        );

        List<Map<String, Object>> filtered = enrichedResults.stream()
            .filter(result -> matchesFilters(result, expectedFilters))
            .collect(Collectors.toList());

        assertFalse(filtered.isEmpty(), "Filtered results should not be empty");
        assertTrue(filtered.size() < enrichedResults.size(),
            "Filtering should exclude at least one non-matching product");

        filtered.forEach(result -> {
            assertEquals("watch", normalizeValue(result.get("category")), "Category should match filter");
            assertTrue(toDouble(result.get("price")) <= 5000.0, "Price should be within filter bounds");
            assertEquals("true", normalizeValue(result.get("inStock")), "Product should be in stock");
        });
    }

    private Map<String, Object> parseMetadata(Map<String, Object> rawResult) {
        Object metadata = rawResult.get("metadata");
        if (metadata instanceof Map<?, ?> map) {
            return map.entrySet().stream()
                .collect(Collectors.toMap(
                    entry -> Objects.toString(entry.getKey()),
                    Map.Entry::getValue
                ));
        }
        if (metadata instanceof String json) {
            try {
                Map<String, Object> parsed = OBJECT_MAPPER.readValue(json, new TypeReference<>() {});
                rawResult.put("metadata", parsed);
                return parsed;
            } catch (Exception ignored) {
                // fall through for malformed metadata
            }
        }
        return Map.of();
    }

    private boolean matchesFilters(Map<String, Object> metadata, Map<String, Object> filters) {
        Set<Map.Entry<String, Object>> entries = filters.entrySet();
        for (Map.Entry<String, Object> entry : entries) {
            String key = entry.getKey();
            Object expectedValue = entry.getValue();

            if ("priceMax".equals(key)) {
                double actualPrice = toDouble(metadata.get("price"));
                if (actualPrice > toDouble(expectedValue)) {
                    return false;
                }
                continue;
            }

            Object actual = metadata.get(key);
            if (actual == null) {
                return false;
            }
            if (!Objects.equals(normalizeValue(actual), normalizeValue(expectedValue))) {
                return false;
            }
        }
        return true;
    }

    private String normalizeValue(Object value) {
        return value == null ? "" : String.valueOf(value).toLowerCase();
    }

    private double toDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String string) {
            return Double.parseDouble(string);
        }
        return 0.0;
    }

    private void seedCatalog() {
        Map<String, String> products = new LinkedHashMap<>();
        products.put("rolex_submariner",
            "Rolex Submariner luxury Swiss dive watch with oystersteel bracelet and ceramic bezel for collectors.");
        products.put("omega_speedmaster",
            "Omega Speedmaster professional chronograph worn on lunar missions and beloved by enthusiasts.");
        products.put("tag_heuer_carrera",
            "TAG Heuer Carrera automatic racing chronograph crafted for high-speed precision timing.");
        products.put("hermes_birkin",
            "Hermes Birkin iconic leather handbag meticulously stitched and prized by fashion icons.");
        products.put("gucci_dionysus",
            "Gucci Dionysus luxury leather handbag with suede lining and signature tiger head closure.");

        products.forEach((productId, description) -> {
            List<Double> embedding = embeddingService.generateEmbedding(
                AIEmbeddingRequest.builder().text(description).build()
            ).getEmbedding();

            Map<String, Object> metadata = switch (productId) {
                case "rolex_submariner" -> Map.of(
                    "category", "watch",
                    "price", 8000,
                    "brand", "Rolex",
                    "inStock", true
                );
                case "omega_speedmaster" -> Map.of(
                    "category", "watch",
                    "price", 4500,
                    "brand", "Omega",
                    "inStock", true
                );
                case "tag_heuer_carrera" -> Map.of(
                    "category", "watch",
                    "price", 3800,
                    "brand", "TAG Heuer",
                    "inStock", false
                );
                case "hermes_birkin" -> Map.of(
                    "category", "handbag",
                    "price", 15000,
                    "brand", "Hermes",
                    "inStock", true
                );
                default -> Map.of(
                    "category", "handbag",
                    "price", 4200,
                    "brand", "Gucci",
                    "inStock", true
                );
            };

            String vectorId = vectorManagementService.storeVector(
                ENTITY_TYPE,
                productId,
                description,
                embedding,
                metadata
            );

            assertNotNull(vectorId, () -> "Vector ID should not be null for entity " + productId);
        });
    }
}
