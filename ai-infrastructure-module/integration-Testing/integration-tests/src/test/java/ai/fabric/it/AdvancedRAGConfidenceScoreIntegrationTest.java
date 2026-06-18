package ai.fabric.it;

import ai.fabric.core.AICoreService;
import ai.fabric.dto.AIGenerationRequest;
import ai.fabric.dto.AIGenerationResponse;
import ai.fabric.dto.AdvancedRAGRequest;
import ai.fabric.dto.AdvancedRAGResponse;
import ai.fabric.dto.RAGRequest;
import ai.fabric.rag.service.AdvancedRAGService;
import ai.fabric.spi.RAGProvider;
import ai.fabric.service.VectorManagementService;
import ai.fabric.embedding.EmbeddingProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

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
    "ai.vector-db.lucene.index-path=./data/test-lucene-index/confidence",
    "ai.vector-db.lucene.similarity-threshold=0.0"
})
class AdvancedRAGConfidenceScoreIntegrationTest {

    private static final String ENTITY_TYPE = "ragproduct-confidence";

    @Autowired
    private RAGProvider ragProvider;

    @Autowired
    private AdvancedRAGService advancedRAGService;

    @Autowired
    private VectorManagementService vectorManagementService;

    @Autowired
    private EmbeddingProvider embeddingProvider;

    @MockBean
    private AICoreService aiCoreService;

    @BeforeEach
    void setUp() {
        when(aiCoreService.generateText(anyString())).thenReturn(
            "confidence calibration\nscoring overview"
        );

        when(aiCoreService.generateContent(any(AIGenerationRequest.class))).thenReturn(
            AIGenerationResponse.builder()
                .content("Confidence scores calibrated against retrieved similarities.")
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
    @DisplayName("Confidence score reflects average similarity of retrieved documents")
    void confidenceScoreMatchesAverageSimilarity() {
        AdvancedRAGResponse response = advancedRAGService.performAdvancedRAG(
            AdvancedRAGRequest.builder()
                .query("precision dive watch with ceramic bezel and helium valve")
                .maxResults(6)
                .maxDocuments(5)
                .expansionLevel(0)
                .rerankingStrategy("semantic")
                .enableHybridSearch(true)
                .enableContextualSearch(false)
                .similarityThreshold(0.0)
                .categories(List.of("watches"))
                .build()
        );

        List<Double> relevanceScores = Optional.ofNullable(response.getRelevanceScores()).orElse(List.of());
        assertFalse(relevanceScores.isEmpty(), "Relevance scores should be present");

        List<Double> validScores = relevanceScores.stream()
            .filter(Objects::nonNull)
            .collect(Collectors.toList());

        assertFalse(validScores.isEmpty(), "Valid relevance scores should be available");

        double averageSimilarity = validScores.stream()
            .mapToDouble(Double::doubleValue)
            .average()
            .orElse(0.0);

        double confidence = Optional.ofNullable(response.getConfidenceScore()).orElse(0.0);

        assertTrue(confidence > 0.0 && confidence <= 1.0,
            "Confidence score should fall within (0, 1]");

        double tolerance = isOnnxOnly() ? 0.15 : 5e-3;
        assertEquals(averageSimilarity, confidence, tolerance,
            "Confidence score should approximate the mean similarity");

        List<Double> similarityValues = Optional.ofNullable(response.getDocuments()).orElse(List.of()).stream()
            .map(doc -> Optional.ofNullable(doc.getSimilarity()).orElse(null))
            .filter(Objects::nonNull)
            .collect(Collectors.toList());

        assertFalse(similarityValues.isEmpty(), "Similarity values should be populated for documents");
        assertTrue(similarityValues.stream().noneMatch(score -> score < 0.0 || score > 1.0),
            "Document similarities should stay within expected bounds");
    }

    private void seedCatalog() {
        IntStream.range(0, 5).forEach(index -> ragProvider.indexContent(
            ENTITY_TYPE,
            ENTITY_TYPE + "_dive_" + index,
            "Professional dive watch with ceramic bezel, helium escape valve, and chronometer certification " + index,
            Map.of(
                "category", "watches",
                "style", "dive",
                "waterResistance", "600m",
                "brand", index % 2 == 0 ? "Pelagos" : "Seamaster"
            )
        ));

        IntStream.range(0, 4).forEach(index -> ragProvider.indexContent(
            ENTITY_TYPE,
            ENTITY_TYPE + "_field_" + index,
            "Field watch with anti-magnetic case and sapphire crystal designed for daily wear " + index,
            Map.of(
                "category", "watches",
                "style", "field",
                "waterResistance", "100m",
                "brand", index % 2 == 0 ? "Explorer" : "Ranger"
            )
        ));

        IntStream.range(0, 3).forEach(index -> ragProvider.indexContent(
            ENTITY_TYPE,
            ENTITY_TYPE + "_dress_" + index,
            "Dress watch with ultra-thin profile, micro-rotor movement, and polished indices " + index,
            Map.of(
                "category", "watches",
                "style", "dress",
                "waterResistance", "30m",
                "brand", index % 2 == 0 ? "Elegance" : "Heritage"
            )
        ));
    }

    private boolean isOnnxOnly() {
        return embeddingProvider != null && "onnx".equalsIgnoreCase(embeddingProvider.getProviderName());
    }
}
