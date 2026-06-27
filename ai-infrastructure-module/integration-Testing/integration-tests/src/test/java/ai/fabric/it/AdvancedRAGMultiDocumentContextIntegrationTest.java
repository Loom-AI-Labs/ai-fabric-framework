package ai.fabric.it;

import ai.fabric.core.AICoreService;
import ai.fabric.dto.AIGenerationRequest;
import ai.fabric.dto.AIGenerationResponse;
import ai.fabric.dto.AdvancedRAGRequest;
import ai.fabric.dto.AdvancedRAGResponse;
import ai.fabric.dto.AdvancedRAGResponse.RAGDocument;
import ai.fabric.spi.RAGProvider;
import ai.fabric.rag.service.AdvancedRAGService;
import ai.fabric.service.VectorManagementService;
import ai.fabric.embedding.EmbeddingProvider;
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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("dev")
class AdvancedRAGMultiDocumentContextIntegrationTest {

    private static final String ENTITY_TYPE = "ragproduct-multicontext";

    private static final List<String> CATEGORIES = List.of(
        "watches",
        "handbags",
        "jewelry",
        "sunglasses",
        "shoes"
    );

    @Autowired
    private RAGProvider ragProvider;

    @Autowired
    private AdvancedRAGService advancedRAGService;

    @Autowired
    private VectorManagementService vectorManagementService;

    @Autowired
    private EmbeddingProvider embeddingProvider;

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private AICoreService aiCoreService;

    @BeforeEach
    void setUp() {
        when(aiCoreService.generateText(anyString())).thenAnswer(invocation -> {
            String prompt = invocation.getArgument(0, String.class);
            if (prompt != null && prompt.contains("Context:")) {
                return "Mock response summarizing multi-category luxury showcase recommendations.";
            }
            return "Luxury showcase planning ideas\n"
                + "Premium accessories pairing guide\n"
                + "High-end watch and jewelry combinations";
        });

        when(aiCoreService.generateContent(any(AIGenerationRequest.class))).thenReturn(
            AIGenerationResponse.builder()
                .content("Mock narrative for multi-document context.")
                .model("mock-openai")
                .build()
        );

        vectorManagementService.clearVectorsByEntityType(ENTITY_TYPE);
        seedCatalog();
    }

    @AfterEach
    void tearDown() {
        vectorManagementService.clearVectorsByEntityType(ENTITY_TYPE);
    }

    @Test
    @DisplayName("RAG aggregates multi-category context for complex queries")
    void ragBuildsContextFromMultipleDocuments() {
        String compositeQuery = "I'm preparing a luxury showcase. Recommend standout watches, "
            + "jewelry, and accessories that pair well together.";

        AdvancedRAGRequest request = AdvancedRAGRequest.builder()
            .query(compositeQuery)
            .maxResults(10)
            .maxDocuments(10)
            .expansionLevel(3)
            .enableHybridSearch(true)
            .enableContextualSearch(true)
            .categories(CATEGORIES)
            .build();

        AdvancedRAGResponse response = advancedRAGService.performAdvancedRAG(request);

        assertNotNull(response, "Response should not be null");
        assertTrue(Boolean.TRUE.equals(response.getSuccess()), "RAG request should succeed");

        List<RAGDocument> documents = Optional.ofNullable(response.getDocuments()).orElse(List.of());
        assertFalse(documents.isEmpty(), "RAG response should include documents");
        int minimumDocCount = isOnnxOnly() ? 6 : 10;
        assertTrue(documents.size() >= minimumDocCount,
            "Expected at least " + minimumDocCount + " documents but got " + documents.size());

        Set<String> categories = documents.stream()
            .map(RAGDocument::getMetadata)
            .filter(Objects::nonNull)
            .map(metadata -> (String) metadata.get("category"))
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());

        int minimumCategoryCount = isOnnxOnly() ? 2 : 3;
        assertTrue(categories.size() >= minimumCategoryCount,
            () -> "Context should span multiple categories but had: " + categories);

        List<Double> scores = Optional.ofNullable(response.getRelevanceScores()).orElse(List.of());
        if (!scores.isEmpty()) {
            for (int index = 0; index < scores.size() - 1; index++) {
                assertTrue(scores.get(index) >= scores.get(index + 1) - 1e-9,
                    "Relevance scores should be non-increasing");
            }
        }

        String context = response.getContext();
        assertNotNull(context, "Context should not be null");
        assertTrue(context.length() < 16_000,
            "Context should stay within the approximate token budget (found " + context.length() + " chars)");

        Set<String> documentIds = documents.stream()
            .map(RAGDocument::getId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
        assertTrue(documentIds.size() >= minimumDocCount,
            "Expected at least " + minimumDocCount + " unique document IDs but found " + documentIds.size());
    }

    private void seedCatalog() {
        IntStream.range(0, 50)
            .mapToObj(this::buildDocument)
            .forEach(document -> ragProvider.indexContent(
                ENTITY_TYPE,
                document.id(),
                document.content(),
                document.metadata()
            ));
    }

    private ProductDocument buildDocument(int index) {
        String category = CATEGORIES.get(index % CATEGORIES.size());
        String brand = switch (category) {
            case "watches" -> index % 2 == 0 ? "Rolex" : "Omega";
            case "handbags" -> index % 2 == 0 ? "Hermès" : "Chanel";
            case "jewelry" -> index % 2 == 0 ? "Cartier" : "Tiffany";
            case "sunglasses" -> index % 2 == 0 ? "Gucci" : "Prada";
            default -> index % 2 == 0 ? "Louboutin" : "Jimmy Choo"; // shoes
        };

        String descriptor = switch (category) {
            case "watches" -> "chronograph crafted for collectors";
            case "handbags" -> "artisan leather bag with iconic accents";
            case "jewelry" -> "statement piece with brilliant-cut diamonds";
            case "sunglasses" -> "UV400 lenses with couture frames";
            default -> "limited edition footwear with couture styling";
        };

        String content = String.format(
            "Luxury %s %s collection %d featuring %s.",
            category,
            brand,
            index,
            descriptor
        );

        Map<String, Object> metadata = Map.of(
            "category", category,
            "brand", brand,
            "collection", "showcase-" + (index % 5),
            "priceTier", index % 2 == 0 ? "premium" : "ultra-premium"
        );

        String documentId = String.format("%s_%s_%d", ENTITY_TYPE, category, index);
        return new ProductDocument(documentId, content, metadata);
    }

    private record ProductDocument(String id, String content, Map<String, Object> metadata) { }

    private boolean isOnnxOnly() {
        return embeddingProvider != null && "onnx".equalsIgnoreCase(embeddingProvider.getProviderName());
    }
}
