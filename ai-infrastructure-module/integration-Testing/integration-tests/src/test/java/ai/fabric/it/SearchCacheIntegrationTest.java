package ai.fabric.it;

import ai.fabric.cache.AICacheConfig;
import ai.fabric.cache.AICacheNames;
import ai.fabric.core.AIEmbeddingService;
import ai.fabric.dto.AIEmbeddingRequest;
import ai.fabric.dto.AISearchRequest;
import ai.fabric.dto.AISearchResponse;
import ai.fabric.search.VectorSearchService;
import ai.fabric.service.VectorManagementService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.context.annotation.Import;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("dev")
@Import(AICacheConfig.class)
@TestPropertySource(properties = "ai.vector-db.lucene.index-path=./data/test-lucene-index/search-cache")
class SearchCacheIntegrationTest {

    private static final String ENTITY_TYPE = "search-cache-product";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Autowired
    private VectorManagementService vectorManagementService;

    @Autowired
    private VectorSearchService vectorSearchService;

    @Autowired
    private AIEmbeddingService embeddingService;

    @Autowired
    private CacheManager cacheManager;

    @BeforeEach
    void setUp() {
        vectorManagementService.clearVectorsByEntityType(ENTITY_TYPE);
        seedCatalog();
        clearSearchCache();
        vectorSearchService.clearMetrics();
    }

    @AfterEach
    void tearDown() {
        vectorManagementService.clearVectorsByEntityType(ENTITY_TYPE);
        clearSearchCache();
    }

    @Test
    @DisplayName("Vector search cache records hits/misses and respects manual invalidation")
    void vectorSearchCacheTracksHitMissAndInvalidation() {
        String query = "heritage chronograph watch";
        List<Double> queryEmbedding = embeddingService.generateEmbedding(
            AIEmbeddingRequest.builder().text(query).build()
        ).getEmbedding();

        AISearchRequest request = AISearchRequest.builder()
            .query(query)
            .entityType(ENTITY_TYPE)
            .limit(5)
            .threshold(0.0)
            .build();

        AISearchResponse initialResponse = vectorSearchService.search(queryEmbedding, request);
        assertNotNull(initialResponse, "Initial search response should not be null");
        assertTrue(initialResponse.getTotalResults() > 0, "Initial search should return candidates");

        Map<String, Object> statsAfterMiss = vectorSearchService.getSearchStatistics();
        assertEquals(1L, statsAfterMiss.get("cacheMisses"), "First search should register a cache miss");
        assertEquals(0L, statsAfterMiss.get("cacheHits"), "No cache hits should be recorded after first search");

        AISearchResponse cachedResponse = vectorSearchService.search(queryEmbedding, request);
        assertSame(initialResponse, cachedResponse, "Second search should return cached response instance");

        Map<String, Object> statsAfterHit = vectorSearchService.getSearchStatistics();
        assertEquals(1L, statsAfterHit.get("cacheHits"), "Second search should register a cache hit");
        assertEquals(1L, statsAfterHit.get("cacheMisses"), "Cache misses should remain at one");
        assertEquals(0.5d, (Double) statsAfterHit.get("cacheHitRate"), 1e-6,
            "Cache hit rate should reflect one hit out of two searches");

        // Update catalog entry and invalidate cache to ensure fresh results are served
        updateFeaturedProduct("v2");
        clearSearchCache();
        vectorSearchService.clearMetrics();

        AISearchResponse refreshedResponse = vectorSearchService.search(queryEmbedding, request);
        Map<String, Object> refreshedStats = vectorSearchService.getSearchStatistics();
        assertEquals(1L, refreshedStats.get("cacheMisses"), "After invalidation, search should incur a miss");
        assertEquals(0L, refreshedStats.get("cacheHits"), "No hits expected immediately after cache clear");

        Map<String, Object> featuredMetadata = findMetadataForProduct(refreshedResponse, "featured-chronograph");
        assertEquals("v2", String.valueOf(featuredMetadata.get("version")),
            "Refreshed search should return updated metadata after cache invalidation");
    }

    private void seedCatalog() {
        storeProduct(
            "featured-chronograph",
            "Heritage-inspired chronograph watch with ceramic bezel and precision movement.",
            Map.of(
                "productId", "featured-chronograph",
                "category", "watch",
                "price", 7400,
                "version", "v1"
            )
        );

        storeProduct(
            "microbrand-field",
            "Durable field watch with luminous dial designed for everyday exploration.",
            Map.of(
                "productId", "microbrand-field",
                "category", "watch",
                "price", 650,
                "version", "v1"
            )
        );

        storeProduct(
            "leather-weekender",
            "Full-grain leather weekender bag with reinforced handles and brass hardware.",
            Map.of(
                "productId", "leather-weekender",
                "category", "luggage",
                "price", 420,
                "version", "v1"
            )
        );
    }

    private void storeProduct(String entityId, String description, Map<String, Object> metadata) {
        List<Double> embedding = embeddingService.generateEmbedding(
            AIEmbeddingRequest.builder().text(description).build()
        ).getEmbedding();

        vectorManagementService.storeVector(
            ENTITY_TYPE,
            entityId,
            description,
            embedding,
            metadata
        );
    }

    private void updateFeaturedProduct(String version) {
        storeProduct(
            "featured-chronograph",
            "Updated heritage chronograph watch featuring upgraded calibre and quick-change strap system.",
            Map.of(
                "productId", "featured-chronograph",
                "category", "watch",
                "price", 7600,
                "version", version
            )
        );
    }

    private void clearSearchCache() {
        Cache cache = cacheManager.getCache(AICacheNames.VECTOR_SEARCH);
        if (cache != null) {
            cache.clear();
        }
    }

    private Map<String, Object> findMetadataForProduct(AISearchResponse response, String productId) {
        AtomicReference<Map<String, Object>> matchingMetadata = new AtomicReference<>();
        response.getResults().forEach(result -> {
            Map<String, Object> metadata = extractMetadata(result);
            if (productId.equals(metadata.get("productId"))) {
                matchingMetadata.set(metadata);
            }
        });

        Map<String, Object> resolved = matchingMetadata.get();
        assertNotNull(resolved, "Expected product metadata should be present in search results");
        return resolved;
    }

    private Map<String, Object> extractMetadata(Map<String, Object> result) {
        Object metadata = result.get("metadata");
        if (metadata instanceof Map<?, ?> map) {
            return map.entrySet().stream()
                .collect(Collectors.toMap(
                    entry -> String.valueOf(entry.getKey()),
                    Map.Entry::getValue
                ));
        }

        if (metadata instanceof String json) {
            try {
                Map<String, Object> parsed = OBJECT_MAPPER.readValue(json, new TypeReference<>() {});
                result.put("metadata", parsed);
                return parsed;
            } catch (Exception ignored) {
                // fall through to empty map if parsing fails
            }
        }

        return Map.of();
    }
}
