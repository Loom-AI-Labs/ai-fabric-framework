package ai.fabric.it;

import ai.fabric.cache.AICacheConfig;
import ai.fabric.core.AIEmbeddingService;
import ai.fabric.dto.AIEmbeddingRequest;
import ai.fabric.dto.AISearchRequest;
import ai.fabric.dto.AISearchResponse;
import ai.fabric.search.VectorSearchService;
import ai.fabric.service.VectorManagementService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("dev")
@Import(AICacheConfig.class)
@TestPropertySource(properties = "ai.vector-db.lucene.index-path=./data/test-lucene-index/search-concurrency")
class SearchConcurrencyIntegrationTest {

    private static final String ENTITY_TYPE = "search-concurrency-product";
    private static final int CONCURRENT_CLIENTS = 24;

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
    @DisplayName("Vector search handles concurrent requests and aggregates cache metrics")
    void vectorSearchHandlesConcurrentRequests() throws Exception {
        String query = "artisanal chronograph watch";
        List<Double> queryEmbedding = embeddingService.generateEmbedding(
            AIEmbeddingRequest.builder().text(query).build()
        ).getEmbedding();

        AISearchRequest request = AISearchRequest.builder()
            .query(query)
            .entityType(ENTITY_TYPE)
            .limit(5)
            .threshold(0.0)
            .build();

        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_CLIENTS);
        List<Callable<AISearchResponse>> tasks = new ArrayList<>();
        for (int i = 0; i < CONCURRENT_CLIENTS; i++) {
            tasks.add(() -> vectorSearchService.search(queryEmbedding, request));
        }

        Instant start = Instant.now();
        List<Future<AISearchResponse>> futures = executor.invokeAll(tasks);
        executor.shutdown();
        executor.awaitTermination(30, TimeUnit.SECONDS);
        Duration elapsed = Duration.between(start, Instant.now());

        AtomicInteger successfulResponses = new AtomicInteger();
        futures.forEach(future -> {
            try {
                AISearchResponse response = future.get();
                assertNotNull(response, "Concurrent search response should not be null");
                assertTrue(response.getTotalResults() > 0, "Each response should contain results");
                successfulResponses.incrementAndGet();
            } catch (Exception e) {
                throw new AssertionError("Concurrent search execution failed", e);
            }
        });

        assertEquals(CONCURRENT_CLIENTS, successfulResponses.get(), "All concurrent searches should complete successfully");
        assertTrue(elapsed.toMillis() < 5_000, "Concurrent searches should finish in a reasonable time budget");

        Map<String, Object> stats = vectorSearchService.getSearchStatistics();
        long totalSearches = ((Number) stats.get("totalSearches")).longValue();
        long cacheHits = ((Number) stats.get("cacheHits")).longValue();
        long cacheMisses = ((Number) stats.get("cacheMisses")).longValue();
        double cacheHitRate = (Double) stats.get("cacheHitRate");

        assertEquals(CONCURRENT_CLIENTS, totalSearches, "Total search count should equal concurrent invocations");
        assertTrue(cacheHits > 0, "Concurrent execution should result in cache hits");
        assertTrue(cacheMisses >= 1, "At least one cache miss is expected on warm-up");
        assertTrue(cacheMisses <= CONCURRENT_CLIENTS, "Cache misses should not exceed total requests");
        assertTrue(cacheHitRate > 0.0, "Cache hit rate should be positive after concurrent warm-up");
    }

    @Test
    @DisplayName("Vector search keeps caches warm across mixed concurrent queries")
    void vectorSearchMaintainsCacheUnderMixedWorkload() throws Exception {
        List<String> queries = List.of(
            "artisanal chronograph watch",
            "carbon fiber dive watch",
            "modular travel organizer"
        );

        Map<String, List<Double>> embeddings = queries.stream()
            .collect(Collectors.toMap(
                query -> query,
                query -> embeddingService.generateEmbedding(
                    AIEmbeddingRequest.builder().text(query).build()
                ).getEmbedding()
            ));

        Map<String, AISearchRequest> requests = queries.stream()
            .collect(Collectors.toMap(
                query -> query,
                query -> AISearchRequest.builder()
                    .query(query)
                    .entityType(ENTITY_TYPE)
                    .limit(5)
                    .threshold(0.0)
                    .build()
            ));

        // Warm cache sequentially so each query has a cached response
        queries.forEach(query -> vectorSearchService.search(embeddings.get(query), requests.get(query)));

        Map<String, Object> warmStats = vectorSearchService.getSearchStatistics();
        assertEquals(queries.size(), ((Number) warmStats.get("cacheMisses")).longValue(),
            "Warm-up should incur exactly one miss per unique query");
        assertEquals(0L, ((Number) warmStats.get("cacheHits")).longValue(),
            "No cache hits expected during initial warm-up");

        vectorSearchService.clearMetrics();

        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_CLIENTS);
        List<Callable<AISearchResponse>> tasks = new ArrayList<>();
        AtomicInteger position = new AtomicInteger();
        for (int i = 0; i < CONCURRENT_CLIENTS; i++) {
            tasks.add(() -> {
                int index = position.getAndIncrement() % queries.size();
                String query = queries.get(index);
                return vectorSearchService.search(embeddings.get(query), requests.get(query));
            });
        }

        List<Future<AISearchResponse>> futures = executor.invokeAll(tasks);
        executor.shutdown();
        executor.awaitTermination(30, TimeUnit.SECONDS);

        for (Future<AISearchResponse> future : futures) {
            AISearchResponse response = future.get();
            assertNotNull(response, "Concurrent mixed query response should not be null");
            assertTrue(response.getTotalResults() > 0, "Responses should contain vector matches");
        }

        Map<String, Object> stats = vectorSearchService.getSearchStatistics();
        long totalSearches = ((Number) stats.get("totalSearches")).longValue();
        long cacheHits = ((Number) stats.get("cacheHits")).longValue();
        long cacheMisses = ((Number) stats.get("cacheMisses")).longValue();
        double cacheHitRate = (Double) stats.get("cacheHitRate");

        assertEquals(CONCURRENT_CLIENTS, totalSearches,
            "Total search count should equal number of concurrent mixed queries");
        assertEquals(0L, cacheMisses, "Cache should remain warm during mixed workload");
        assertEquals(CONCURRENT_CLIENTS, cacheHits, "All concurrent lookups should hit the cache");
        assertEquals(1.0d, cacheHitRate, 1e-6,
            "Cache hit rate should be perfect once warm");
    }

    private void seedCatalog() {
        storeProduct(
            "artisan-chronograph",
            "Artisanal chronograph watch with hand-finished movement and domed sapphire crystal.",
            Map.of(
                "productId", "artisan-chronograph",
                "category", "watch",
                "price", 9200
            )
        );

        storeProduct(
            "carbon-fiber-dive",
            "Carbon fiber dive watch rated to 600m with helium escape valve and ceramic bezel.",
            Map.of(
                "productId", "carbon-fiber-dive",
                "category", "watch",
                "price", 5800
            )
        );

        storeProduct(
            "travel-organizer",
            "Modular travel organizer with RFID shielding panels and quick-access passport sleeve.",
            Map.of(
                "productId", "travel-organizer",
                "category", "accessory",
                "price", 210
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

    private void clearSearchCache() {
        Cache cache = cacheManager.getCache("vectorSearch");
        if (cache != null) {
            cache.clear();
        }
    }
}
