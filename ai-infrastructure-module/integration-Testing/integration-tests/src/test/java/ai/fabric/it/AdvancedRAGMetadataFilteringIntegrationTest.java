package ai.fabric.it;

import ai.fabric.core.AICoreService;
import ai.fabric.dto.AIGenerationRequest;
import ai.fabric.dto.AIGenerationResponse;
import ai.fabric.dto.AdvancedRAGRequest;
import ai.fabric.dto.AdvancedRAGResponse;
import ai.fabric.dto.AdvancedRAGResponse.RAGDocument;
import ai.fabric.rag.service.AdvancedRAGService;
import ai.fabric.spi.RAGProvider;
import ai.fabric.service.VectorManagementService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("dev")
class AdvancedRAGMetadataFilteringIntegrationTest {

    private static final String ENTITY_TYPE = "ragproduct-metadata";

    @Autowired
    private RAGProvider ragProvider;

    @Autowired
    private AdvancedRAGService advancedRAGService;

    @Autowired
    private VectorManagementService vectorManagementService;

    @org.springframework.boot.test.mock.mockito.MockBean
    private AICoreService aiCoreService;

    @BeforeEach
    void setUp() {
        when(aiCoreService.generateText(anyString())).thenAnswer(invocation -> {
            String prompt = invocation.getArgument(0, String.class);
            if (prompt != null && prompt.contains("Context:")) {
                return "Mock contextual synthesis highlighting Rolex watches with premium attributes.";
            }
            return "Rolex luxury showcase recommendations\n"
                + "Premium timepieces for collectors\n"
                + "Luxury accessories pairing ideas";
        });

        when(aiCoreService.generateContent(any(AIGenerationRequest.class))).thenReturn(
            AIGenerationResponse.builder()
                .content("Mock content generated for advanced RAG response.")
                .model("mock-openai")
                .build()
        );

        vectorManagementService.clearAllVectors();
        vectorManagementService.clearVectorsByEntityType(ENTITY_TYPE);
        seedProductCatalog();
    }

    @AfterEach
    void tearDown() {
        vectorManagementService.clearVectorsByEntityType(ENTITY_TYPE);
    }

    @Test
    @DisplayName("Advanced RAG metadata filters restrict results to matching attributes")
    void metadataFiltersRestrictResultsToRequestedAttributes() {
        String baseQuery = "Rolex luxury timepiece";

        AdvancedRAGResponse unfiltered = advancedRAGService.performAdvancedRAG(
            AdvancedRAGRequest.builder()
                .query(baseQuery)
                .expansionLevel(2)
                .enableHybridSearch(true)
                .enableContextualSearch(false)
                .categories(List.of("watches", "jewelry", "accessories"))
                .maxResults(30)
                .maxDocuments(10)
                .rerankingStrategy("hybrid")
                .contextOptimizationLevel("medium")
                .build()
        );

        assertNotNull(unfiltered, "Unfiltered response should not be null");
        assertTrue(Boolean.TRUE.equals(unfiltered.getSuccess()), "Unfiltered request should succeed");
        List<RAGDocument> unfilteredDocs = Optional.ofNullable(unfiltered.getDocuments()).orElse(List.of());
        assertFalse(unfilteredDocs.isEmpty(), "Unfiltered response should contain documents");

        Map<String, Object> metadataFilters = Map.of(
            "category", "watches",
            "brand", "Rolex",
            "priceRange", "premium"
        );

        AdvancedRAGResponse filtered = advancedRAGService.performAdvancedRAG(
            AdvancedRAGRequest.builder()
                .query(baseQuery)
                .expansionLevel(3)
                .enableHybridSearch(true)
                .enableContextualSearch(true)
                .categories(List.of("watches"))
                .maxResults(30)
                .maxDocuments(10)
                .rerankingStrategy("semantic")
                .contextOptimizationLevel("high")
                .filters(metadataFilters)
                .build()
        );

        assertNotNull(filtered, "Filtered response should not be null");
        assertTrue(Boolean.TRUE.equals(filtered.getSuccess()), "Filtered request should succeed");

        List<RAGDocument> filteredDocs = Optional.ofNullable(filtered.getDocuments()).orElse(List.of());
        assertFalse(filteredDocs.isEmpty(), "Filtered response should return at least one document");

        Set<String> filteredCategories = filteredDocs.stream()
            .map(RAGDocument::getMetadata)
            .filter(Objects::nonNull)
            .map(metadata -> (String) metadata.get("category"))
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
        assertEquals(Set.of("watches"), filteredCategories,
            () -> "Filtered response should only include watch metadata but had categories: " + filteredCategories);

        Set<String> filteredIds = filteredDocs.stream()
            .map(RAGDocument::getId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
        assertTrue(filteredIds.stream().allMatch(id -> id.contains("_watches_")),
            () -> "Filtered response should only include watch documents but had: " + filteredIds);

        filteredDocs.forEach(document -> {
            Map<String, Object> metadata = document.getMetadata();
            assertNotNull(metadata, "Document metadata should not be null");
            assertTrue(valuesMatch(metadata.get("category"), "watches"),
                () -> "Expected category 'watches' but was " + metadata.get("category"));
            assertTrue(valuesMatch(metadata.get("brand"), "Rolex"),
                () -> "Expected brand 'Rolex' but was " + metadata.get("brand"));
            assertTrue(valuesMatch(metadata.get("priceRange"), "premium"),
                () -> "Expected priceRange 'premium' but was " + metadata.get("priceRange"));
        });

        Set<String> unfilteredCategories = unfilteredDocs.stream()
            .map(RAGDocument::getMetadata)
            .filter(Objects::nonNull)
            .map(metadata -> (String) metadata.get("category"))
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
        assertTrue(unfilteredCategories.size() > 1,
            () -> "Unfiltered response should span multiple categories but had: " + unfilteredCategories);
    }

    private boolean valuesMatch(Object actual, String expected) {
        return actual != null && expected.equalsIgnoreCase(String.valueOf(actual));
    }

    private void seedProductCatalog() {
        List<String> categories = List.of("watches", "jewelry", "accessories");

        IntStream.range(0, 30)
            .mapToObj(index -> buildProductDocument(index, categories.get(index % categories.size())))
            .forEach(document -> ragProvider.indexContent(ENTITY_TYPE, document.id(), document.content(), document.metadata()));
    }

    private ProductDocument buildProductDocument(int index, String category) {
        String brand = category.equals("watches")
            ? "Rolex"
            : switch (index % 3) {
                case 0 -> "Omega";
                case 1 -> "Cartier";
                default -> "Patek Philippe";
            };

        String descriptor = switch (category) {
            case "watches" -> "chronograph timepiece crafted for horology enthusiasts";
            case "jewelry" -> "diamond bracelet inspired by haute horlogerie motifs";
            default -> "luxury accessory pairing with prestige chronographs";
        };

        String content = String.format(
            "Luxury %s %s collection %d featuring %s and artisan craftsmanship for collectors.",
            category,
            brand,
            index,
            descriptor
        );

        Map<String, Object> metadata = Map.of(
            "category", category,
            "brand", brand,
            "priceRange", index % 2 == 0 ? "premium" : "ultra-premium",
            "style", index % 2 == 0 ? "classic" : "avant-garde"
        );

        String documentId = String.format("%s_%s_%d", ENTITY_TYPE, category, index);
        return new ProductDocument(documentId, content, metadata);
    }

    private record ProductDocument(String id, String content, Map<String, Object> metadata) { }
}
