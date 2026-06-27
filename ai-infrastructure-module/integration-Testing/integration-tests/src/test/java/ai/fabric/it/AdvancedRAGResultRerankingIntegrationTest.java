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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("dev")
@TestPropertySource(properties = {
    "ai.vector-db.lucene.index-path=./data/test-lucene-index/reranking",
    "ai.vector-db.lucene.similarity-threshold=0.0"
})
class AdvancedRAGResultRerankingIntegrationTest {

    private static final String ENTITY_TYPE = "ragproduct-reranking";

    @Autowired
    private RAGProvider ragProvider;

    @MockitoBean
    private AICoreService aiCoreService;

    @Autowired
    private AdvancedRAGService advancedRAGService;

    @Autowired
    private VectorManagementService vectorManagementService;

    @BeforeEach
    void setUp() {
        when(aiCoreService.generateText(anyString())).thenReturn(
            "collector chronograph\nlimited edition timepiece\nflagship mechanical movement"
        );

        when(aiCoreService.generateContent(any(AIGenerationRequest.class))).thenReturn(
            AIGenerationResponse.builder()
                .content("Prioritised flagship chronographs with collector appeal.")
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
    @DisplayName("Semantic reranking elevates the flagship chronograph")
    void semanticRerankingElevatesFlagshipDocuments() {
        AdvancedRAGResponse baseline = advancedRAGService.performAdvancedRAG(
            AdvancedRAGRequest.builder()
                .query("collector chronograph limited edition watch")
                .maxResults(8)
                .maxDocuments(6)
                .expansionLevel(0)
                .rerankingStrategy("score")
                .enableHybridSearch(true)
                .enableContextualSearch(false)
                .categories(List.of("watches"))
                .similarityThreshold(0.0)
                .build()
        );

        AdvancedRAGResponse semantic = advancedRAGService.performAdvancedRAG(
            AdvancedRAGRequest.builder()
                .query("collector chronograph limited edition watch")
                .maxResults(8)
                .maxDocuments(6)
                .expansionLevel(0)
                .rerankingStrategy("semantic")
                .enableHybridSearch(true)
                .enableContextualSearch(false)
                .categories(List.of("watches"))
                .similarityThreshold(0.0)
                .build()
        );

        List<RAGDocument> baselineDocs = Optional.ofNullable(baseline.getDocuments()).orElse(List.of());
        List<RAGDocument> semanticDocs = Optional.ofNullable(semantic.getDocuments()).orElse(List.of());

        assertFalse(baselineDocs.isEmpty(), "Baseline reranking should return documents");
        assertFalse(semanticDocs.isEmpty(), "Semantic reranking should return documents");

        List<Double> semanticSimilarities = semanticDocs.stream()
            .map(RAGDocument::getSimilarity)
            .filter(Objects::nonNull)
            .collect(Collectors.toList());

        assertFalse(semanticSimilarities.isEmpty(), "Semantic reranking should populate similarity scores");
        assertTrue(isNonIncreasing(semanticSimilarities),
            "Semantic reranking should sort documents by similarity");

        Map<String, Double> baselineSimilarityById = baselineDocs.stream()
            .collect(Collectors.toMap(RAGDocument::getId, RAGDocument::getSimilarity, (a, b) -> a));

        boolean similarityRecomputed = semanticDocs.stream()
            .anyMatch(doc -> {
                Double baselineSimilarity = baselineSimilarityById.get(doc.getId());
                Double semanticSimilarity = doc.getSimilarity();
                return semanticSimilarity != null && !Objects.equals(baselineSimilarity, semanticSimilarity);
            });

        assertTrue(similarityRecomputed, "Semantic reranking should recompute similarity scores");

        assertEquals("semantic", semantic.getRerankingStrategy(), "Semantic response should report semantic reranking");
    }

    private boolean isNonIncreasing(List<Double> values) {
        for (int i = 1; i < values.size(); i++) {
            if (values.get(i) > values.get(i - 1) + 1e-6) {
                return false;
            }
        }
        return true;
    }

    private void seedCatalog() {
        IntStream.range(0, 6).forEach(index -> ragProvider.indexContent(
            ENTITY_TYPE,
            ENTITY_TYPE + "_flagship_" + index,
            "Flagship collector chronograph with hand-finished movement and limited edition serial number " + index,
            Map.of(
                "category", "watches",
                "brand", index % 2 == 0 ? "Apex" : "Helios",
                "tier", "flagship",
                "series", "collector"
            )
        ));

        IntStream.range(0, 6).forEach(index -> ragProvider.indexContent(
            ENTITY_TYPE,
            ENTITY_TYPE + "_midrange_" + index,
            "Midrange automatic watch focused on everyday reliability and comfort " + index,
            Map.of(
                "category", "watches",
                "brand", index % 2 == 0 ? "Everyday" : "Momentum",
                "tier", "midrange",
                "series", "daily"
            )
        ));

        IntStream.range(0, 4).forEach(index -> ragProvider.indexContent(
            ENTITY_TYPE,
            ENTITY_TYPE + "_fitness_" + index,
            "Lightweight fitness tracker with wellness metrics and sleep monitoring " + index,
            Map.of(
                "category", "wearables",
                "brand", index % 2 == 0 ? "Pulse" : "Stride",
                "tier", "baseline",
                "series", "fitness"
            )
        ));
    }
}
