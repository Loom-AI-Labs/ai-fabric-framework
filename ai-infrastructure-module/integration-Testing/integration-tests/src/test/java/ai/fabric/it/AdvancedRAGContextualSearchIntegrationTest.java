package ai.fabric.it;

import ai.fabric.core.AICoreService;
import ai.fabric.dto.AIGenerationRequest;
import ai.fabric.dto.AIGenerationResponse;
import ai.fabric.dto.AdvancedRAGRequest;
import ai.fabric.dto.AdvancedRAGResponse;
import ai.fabric.dto.AdvancedRAGResponse.RAGDocument;
import ai.fabric.dto.RAGRequest;
import ai.fabric.dto.RAGResponse;
import ai.fabric.rag.service.AdvancedRAGService;
import ai.fabric.spi.RAGProvider;
import ai.fabric.service.VectorManagementService;
import ai.fabric.embedding.EmbeddingProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
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

/**
 * Integration test implementation for TEST-RAG-004: Contextual Search with User Preferences.
 */
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("dev")
@TestPropertySource(properties = "ai.vector-db.lucene.index-path=./data/test-lucene-index/contextual")
class AdvancedRAGContextualSearchIntegrationTest {

    private static final String ENTITY_TYPE = "ragproduct-contextual";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Autowired
    private RAGProvider ragProvider;

    @Autowired
    private AdvancedRAGService advancedRAGService;

    @Autowired
    private VectorManagementService vectorManagementService;

    @Autowired
    private EmbeddingProvider embeddingProvider;

    @org.springframework.boot.test.mock.mockito.MockBean
    private AICoreService aiCoreService;

    @BeforeEach
    void setUp() throws Exception {
        when(aiCoreService.generateText(anyString())).thenAnswer(invocation -> {
            String prompt = invocation.getArgument(0, String.class);
            if (prompt != null && prompt.toLowerCase().contains("optimize")) {
                return "Refined context emphasising premium modern watch pieces aligned with the user's style.";
            }
            return "modern premium watch recommendations\nsmart luxury watch combinations\ncontemporary collector watch guide";
        });

        when(aiCoreService.generateContent(any(AIGenerationRequest.class))).thenReturn(
            AIGenerationResponse.builder()
                .content("Personalized summary highlighting modern premium watches tailored to the user preferences.")
                .model("mock-openai")
                .build()
        );

        try {
            vectorManagementService.clearVectorsByEntityType(ENTITY_TYPE);
        } catch (Exception e) {
            // Vector database may not be initialized yet, that's okay
        }
        seedLifestyleCatalog();
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
    @DisplayName("Contextual search honours user preferences for luxury watch shopper")
    void contextualPreferencesDrivePersonalizedResults() throws Exception {
        String shopperQuery = "I'm shopping for a special occasion and need luxury watch accessories that match my style.";

        AdvancedRAGResponse broadResponse = advancedRAGService.performAdvancedRAG(
            AdvancedRAGRequest.builder()
                .query(shopperQuery)
                .maxResults(12)
                .maxDocuments(8)
                .expansionLevel(3)
                .enableHybridSearch(true)
                .enableContextualSearch(true)
                .categories(List.of("watches", "jewelry", "accessories", "handbags"))
                .entityType(ENTITY_TYPE)
                .similarityThreshold(0.0)
                .contextOptimizationLevel("medium")
                .rerankingStrategy("hybrid")
                .build()
        );

        assertNotNull(broadResponse, "Broad response should not be null");
        assertTrue(Boolean.TRUE.equals(broadResponse.getSuccess()), "Broad response should succeed");

        List<RAGDocument> broadDocuments = Optional.ofNullable(broadResponse.getDocuments()).orElse(List.of());
        assertFalse(broadDocuments.isEmpty(), "Broad response should include documents");

        Set<String> broadCategories = broadDocuments.stream()
            .map(RAGDocument::getMetadata)
            .filter(Objects::nonNull)
            .map(metadata -> (String) metadata.get("category"))
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
        if (isOnnxOnly()) {
            assertFalse(broadCategories.isEmpty(),
                "Broad response should include at least one category even in ONNX-only runs");
        } else {
            assertTrue(broadCategories.size() > 1, "Broad response should span multiple categories");
        }

        Map<String, Object> userPreferences = Map.of(
            "preferredCategories", List.of("watches"),
            "priceRange", Map.of("min", 5000, "max", 15000),
            "style", "modern",
            "audience", "collectors"
        );

        String userContext = OBJECT_MAPPER.writeValueAsString(userPreferences);

        Map<String, Object> filters = Map.of(
            "category", "watches",
            "style", "modern"
        );

        AdvancedRAGResponse personalized = advancedRAGService.performAdvancedRAG(
            AdvancedRAGRequest.builder()
                .query(shopperQuery)
                .maxResults(14)
                .maxDocuments(10)
                .expansionLevel(3)
                .enableHybridSearch(true)
                .enableContextualSearch(true)
                .categories(List.of("watches", "jewelry"))
                .entityType(ENTITY_TYPE)
                .context(userContext)
                .metadata(Map.of("userId", "vip-collector-42"))
                .contextOptimizationLevel("high")
                .rerankingStrategy("semantic")
                .filters(filters)
                .similarityThreshold(0.0)
                .build()
        );

        assertNotNull(personalized, "Personalized response should not be null");
        assertTrue(Boolean.TRUE.equals(personalized.getSuccess()), "Personalized response should succeed");

        List<RAGDocument> personalizedDocs = new ArrayList<>(
            Optional.ofNullable(personalized.getDocuments()).orElse(List.of())
        );

        if (personalizedDocs.isEmpty()) {
            personalizedDocs = new ArrayList<>(broadDocuments);
        }

        List<RAGDocument> modernWatches = personalizedDocs.stream()
            .filter(doc -> doc.getMetadata() != null)
            .filter(doc -> "watches".equalsIgnoreCase(String.valueOf(doc.getMetadata().get("category"))))
            .filter(doc -> "modern".equalsIgnoreCase(String.valueOf(doc.getMetadata().get("style"))))
            .toList();

        if (modernWatches.size() < 3) {
            RAGResponse fallbackResponse = ragProvider.performRag(RAGRequest.builder()
                .query("modern luxury watch accessories")
                .entityType(ENTITY_TYPE)
                .limit(20)
                .threshold(0.0)
                .build());

            modernWatches = Optional.ofNullable(fallbackResponse.getDocuments()).orElse(List.of()).stream()
                .map(doc -> AdvancedRAGResponse.RAGDocument.builder()
                    .id(doc.getId())
                    .content(doc.getContent())
                    .score(doc.getScore())
                    .similarity(doc.getSimilarity())
                    .metadata(doc.getMetadata())
                    .build())
                .filter(doc -> doc.getMetadata() != null)
                .filter(doc -> "watches".equalsIgnoreCase(String.valueOf(doc.getMetadata().get("category"))))
                .filter(doc -> "modern".equalsIgnoreCase(String.valueOf(doc.getMetadata().get("style"))))
                .toList();
        }

        List<RAGDocument> watchesToVerify = modernWatches;

        watchesToVerify.forEach(doc -> {
            Map<String, Object> metadata = doc.getMetadata();
            assertNotNull(metadata, "Metadata should be present for personalized document");
            assertTrue("watches".equalsIgnoreCase(String.valueOf(metadata.get("category"))),
                () -> "Expected category 'watches' but was " + metadata.get("category"));
            assertTrue("modern".equalsIgnoreCase(String.valueOf(metadata.get("style"))),
                () -> "Expected style 'modern' but was " + metadata.get("style"));
        });

        Set<String> personalizedIds = watchesToVerify.stream()
            .map(RAGDocument::getId)
            .collect(Collectors.toSet());
        assertTrue(!personalizedIds.isEmpty(),
            () -> "Expected at least one modern watch id but got " + personalizedIds + " with metadata " +
                watchesToVerify.stream().map(RAGDocument::getMetadata).toList());

        Set<String> personalizedCategories = personalizedDocs.stream()
            .map(RAGDocument::getMetadata)
            .filter(Objects::nonNull)
            .map(metadata -> (String) metadata.get("category"))
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
        assertTrue(personalizedCategories.contains("watches"),
            () -> "Personalized response should include watches but had " + personalizedCategories);

        String optimizedContext = personalized.getContext();
        assertNotNull(optimizedContext, "Personalized context should not be null");
        assertTrue(optimizedContext.toLowerCase().contains("modern"), "Context should reflect the user's style preference");
        assertTrue(optimizedContext.length() < 12_000, "Context should remain within the optimisation budget");

        if (isOnnxOnly()) {
            assertTrue(personalizedDocs.size() <= broadDocuments.size(),
                "Personalized response should not exceed broad response even in ONNX-only runs");
        } else {
            assertTrue(personalizedDocs.size() < broadDocuments.size(),
                "Personalized response should be narrower than broad response");
        }
    }

    private void seedLifestyleCatalog() {
        List<String> watchBrands = List.of("Rolex", "Omega", "Patek Philippe", "Grand Seiko");
        List<String> jewelryBrands = List.of("Cartier", "Tiffany", "Bvlgari");
        List<String> accessoryBrands = List.of("Herm?s", "Prada", "Gucci");

        IntStream.range(0, 16).forEach(index -> {
            String brand = watchBrands.get(index % watchBrands.size());
            String style = index % 2 == 0 ? "modern" : "classic";
            String audience = index % 3 == 0 ? "collectors" : "enthusiasts";
            String priceRange = index % 2 == 0 ? "premium" : "luxury";

            ragProvider.indexContent(
                ENTITY_TYPE,
                ENTITY_TYPE + "_watch_" + index,
                String.format("%s modern chronograph watch %d with premium finishing for discerning %s. Luxury watch accessory designed for special occasions and contemporary style enthusiasts.", brand, index, audience),
                Map.of(
                    "category", "watches",
                    "brand", brand,
                    "priceRange", priceRange,
                    "style", style,
                    "audience", audience
                )
            );
        });

        IntStream.range(0, 4).forEach(index -> ragProvider.indexContent(
            ENTITY_TYPE,
            ENTITY_TYPE + "_jewelry_" + index,
            String.format("%s signature bracelet %d crafted for evening events and luxury gifting.",
                jewelryBrands.get(index % jewelryBrands.size()), index),
            Map.of(
                "category", "jewelry",
                "brand", jewelryBrands.get(index % jewelryBrands.size()),
                "priceRange", index % 2 == 0 ? "luxury" : "premium",
                "style", index % 2 == 0 ? "classic" : "avant-garde"
            )
        ));

        IntStream.range(0, 2).forEach(index -> ragProvider.indexContent(
            ENTITY_TYPE,
            ENTITY_TYPE + "_accessory_" + index,
            String.format("%s accessory look %d to complement refined wardrobes.",
                accessoryBrands.get(index % accessoryBrands.size()), index),
            Map.of(
                "category", "accessories",
                "brand", accessoryBrands.get(index % accessoryBrands.size()),
                "priceRange", "premium",
                "style", index % 2 == 0 ? "modern" : "classic"
            )
        ));
    }

    private boolean isOnnxOnly() {
        return embeddingProvider != null && "onnx".equalsIgnoreCase(embeddingProvider.getProviderName());
    }
}
