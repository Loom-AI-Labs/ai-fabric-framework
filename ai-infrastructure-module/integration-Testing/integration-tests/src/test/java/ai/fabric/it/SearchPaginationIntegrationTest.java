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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("dev")
@TestPropertySource(properties = "ai.vector-db.lucene.index-path=./data/test-lucene-index/search-pagination-consistency")
class SearchPaginationIntegrationTest {

    private static final String ENTITY_TYPE = "searchpaginationproduct";

    @Autowired
    private VectorManagementService vectorManagementService;

    @Autowired
    private AIEmbeddingService embeddingService;

    @BeforeEach
    void setUp() {
        try {
            vectorManagementService.clearVectorsByEntityType(ENTITY_TYPE);
        } catch (Exception e) {
            // Vector database may not be initialized yet, that's okay
        }
        seedProducts(25);
    }

    @AfterEach
    void tearDown() {
        try {
            vectorManagementService.clearVectorsByEntityType(ENTITY_TYPE);
        } catch (Exception e) {
            // Ignore cleanup errors
        }
    }

    @Test
    @DisplayName("Vector search results can be paginated deterministically")
    void searchResultsSupportDeterministicPagination() {
        String query = "premium product";
        List<Double> queryEmbedding = embeddingService.generateEmbedding(
            AIEmbeddingRequest.builder().text(query).build()
        ).getEmbedding();

        AISearchRequest request = AISearchRequest.builder()
            .query(query)
            .entityType(ENTITY_TYPE)
            .limit(25)
            .threshold(0.0)
            .build();

        AISearchResponse response = vectorManagementService.searchByEntityType(queryEmbedding, ENTITY_TYPE, request.getLimit(), request.getThreshold());

        List<String> orderedIds = response.getResults().stream()
            .map(result -> String.valueOf(result.get("id")))
            .collect(Collectors.toList());

        assertEquals(25, orderedIds.size(), "Expected full result set for pagination test");

        List<List<String>> pages = paginate(orderedIds, 5);

        assertEquals(5, pages.size(), "Five pages expected for 25 items with page size 5");
        pages.forEach(page -> assertEquals(5, page.size(), "Each page should contain exactly five items"));

        Set<String> uniqueIds = new HashSet<>();
        pages.forEach(uniqueIds::addAll);

        assertEquals(new HashSet<>(orderedIds), uniqueIds, "Pagination should preserve all result IDs without duplication");

        List<String> reconstructed = pages.stream()
            .flatMap(List::stream)
            .collect(Collectors.toList());

        assertEquals(orderedIds, reconstructed, "Reassembled pages should match original ordering");

        List<String> beyondLastPage = paginate(orderedIds, 5).stream()
            .skip(5)
            .findFirst()
            .orElseGet(List::of);

        assertTrue(beyondLastPage.isEmpty(), "Pagination beyond available pages should yield an empty list");
    }

    private List<List<String>> paginate(List<String> ids, int pageSize) {
        List<List<String>> pages = new ArrayList<>();
        for (int start = 0; start < ids.size(); start += pageSize) {
            int end = Math.min(start + pageSize, ids.size());
            pages.add(ids.subList(start, end));
        }
        return pages;
    }

    private void seedProducts(int count) {
        for (int i = 1; i <= count; i++) {
            String productId = "product_" + i;
            String description = "Premium product number " + i + " crafted with exceptional materials and attention to detail.";

            List<Double> embedding = embeddingService.generateEmbedding(
                AIEmbeddingRequest.builder().text(description).build()
            ).getEmbedding();

            Map<String, Object> metadata = Map.of(
                "category", i % 2 == 0 ? "accessory" : "watch",
                "price", 1000 + (i * 120),
                "rank", i
            );

            vectorManagementService.storeVector(
                ENTITY_TYPE,
                productId,
                description,
                embedding,
                metadata
            );
        }
    }
}
