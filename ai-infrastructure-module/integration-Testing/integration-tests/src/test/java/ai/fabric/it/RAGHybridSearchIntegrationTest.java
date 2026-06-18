package ai.fabric.it;

import ai.fabric.dto.AISearchResponse;
import ai.fabric.dto.RAGRequest;
import ai.fabric.dto.RAGResponse;
import ai.fabric.spi.RAGProvider;
import ai.fabric.service.VectorManagementService;
import ai.fabric.rag.VectorDatabaseService;
import ai.fabric.vector.VectorDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Integration test implementation for TEST-RAG-003 (Hybrid Search behavior).
 */
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("dev")
@TestPropertySource(properties = "ai.vector-db.lucene.index-path=./data/test-lucene-index-hybrid")
class RAGHybridSearchIntegrationTest {

    private static final String ENTITY_TYPE = "ragproducthybrid";
    private static final String TARGET_QUERY = "Swiss automatic watch with sapphire crystal and dive bezel";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    @Autowired
    private RAGProvider ragProvider;

    @Autowired
    private VectorManagementService vectorManagementService;

    @Autowired
    private VectorDatabaseService vectorDatabaseService;

    @org.springframework.boot.test.mock.mockito.MockBean
    private VectorDatabase vectorDatabase;

    @BeforeEach
    void setUp() {
        when(vectorDatabase.search(any(), any())).thenAnswer(invocation -> {
            AISearchResponse response = vectorDatabaseService.search(invocation.getArgument(0), invocation.getArgument(1));

            List<Map<String, Object>> normalizedResults = response.getResults().stream()
                .map(result -> {
                    Map<String, Object> mutable = result != null ? result.entrySet().stream()
                        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)) : Map.<String, Object>of();

                    Object metadata = mutable.get("metadata");
                    if (metadata instanceof String metadataStr) {
                        try {
                            Map<String, Object> parsed = OBJECT_MAPPER.readValue(metadataStr, MAP_TYPE);
                            mutable.put("metadata", parsed);
                        } catch (Exception ignored) {
                            // Leave metadata as-is if parsing fails
                        }
                    }
                    return mutable;
                })
                .collect(Collectors.toList());

            return AISearchResponse.builder()
                .results(normalizedResults)
                .totalResults(response.getTotalResults())
                .maxScore(response.getMaxScore())
                .processingTimeMs(response.getProcessingTimeMs())
                .requestId(response.getRequestId())
                .query(response.getQuery())
                .model(response.getModel())
                .build();
        });

        try {
            vectorManagementService.clearVectorsByEntityType(ENTITY_TYPE);
        } catch (Exception e) {
            // Vector database may not be initialized yet, that's okay
            // Log will be handled by VectorManagementService
        }
        seedHybridCatalog();
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
    @DisplayName("Hybrid search flag toggles without degrading retrieval results")
    void hybridSearchYieldsConsistentResults() {
        RAGRequest vectorOnly = RAGRequest.builder()
            .query(TARGET_QUERY)
            .entityType(ENTITY_TYPE)
            .limit(8)
            .threshold(0.0)
            .enableHybridSearch(false)
            .enableContextualSearch(false)
            .build();

        RAGRequest hybridEnabled = RAGRequest.builder()
            .query(TARGET_QUERY)
            .entityType(ENTITY_TYPE)
            .limit(8)
            .threshold(0.0)
            .enableHybridSearch(true)
            .enableContextualSearch(false)
            .build();

        RAGResponse vectorResponse = ragProvider.performRAGQuery(vectorOnly);
        RAGResponse hybridResponse = ragProvider.performRAGQuery(hybridEnabled);

        assertNotNull(vectorResponse, "Vector-only response should not be null");
        assertNotNull(hybridResponse, "Hybrid response should not be null");

        assertTrue(Boolean.TRUE.equals(vectorResponse.getSuccess()), "Vector-only request should succeed");
        assertTrue(Boolean.TRUE.equals(hybridResponse.getSuccess()), "Hybrid request should succeed");

        List<RAGResponse.RAGDocument> vectorDocs = Optional.ofNullable(vectorResponse.getDocuments()).orElse(List.of());
        List<RAGResponse.RAGDocument> hybridDocs = Optional.ofNullable(hybridResponse.getDocuments()).orElse(List.of());

        assertFalse(vectorDocs.isEmpty(), "Vector-only response should return documents");
        assertFalse(hybridDocs.isEmpty(), "Hybrid response should return documents");

        assertTrue(Boolean.FALSE.equals(vectorResponse.getHybridSearchUsed()), "Vector-only response should flag hybrid as false");
        assertTrue(Boolean.TRUE.equals(hybridResponse.getHybridSearchUsed()), "Hybrid response should flag hybrid as true");

        Set<String> vectorDocIds = vectorDocs.stream()
            .map(RAGResponse.RAGDocument::getId)
            .filter(id -> id != null && !id.isBlank())
            .collect(Collectors.toSet());

        Set<String> hybridDocIds = hybridDocs.stream()
            .map(RAGResponse.RAGDocument::getId)
            .filter(id -> id != null && !id.isBlank())
            .collect(Collectors.toSet());

        assertTrue(hybridDocIds.containsAll(vectorDocIds),
            "Hybrid search should include at least the vector-only document set");

        assertNotNull(hybridResponse.getContext(), "Hybrid context should not be null");
        assertTrue(hybridResponse.getContext().toLowerCase().contains("watch"),
            "Hybrid context should reference the watch domain");
    }

    private void seedHybridCatalog() {
        IntStream.range(0, 24).forEach(index -> {
            String id = ENTITY_TYPE + "_" + index;
            String brand = switch (index % 4) {
                case 0 -> "Rolex";
                case 1 -> "Omega";
                case 2 -> "Tag Heuer";
                default -> "Breitling";
            };

            String focus = switch (index % 3) {
                case 0 -> "sapphire crystal";
                case 1 -> "ceramic bezel";
                default -> "chronograph movement";
            };

            String description = String.format(
                "%s Swiss automatic watch %d featuring %s, stainless steel case, and dive-ready build.",
                brand,
                index,
                focus
            );

            ragProvider.indexContent(
                ENTITY_TYPE,
                id,
                description,
                Map.of(
                    "brand", brand,
                    "feature", focus,
                    "category", "luxury-watch"
                )
            );
        });

        // Add a couple of explicit keyword-heavy entries to emphasise hybrid keyword relevance.
        ragProvider.indexContent(
            ENTITY_TYPE,
            ENTITY_TYPE + "_deep_dive_1",
            "Swiss automatic dive watch with sapphire crystal, helium escape valve, and luminous bezel for deep sea expeditions.",
            Map.of(
                "brand", "Rolex",
                "feature", "sapphire crystal",
                "category", "luxury-watch"
            )
        );

        ragProvider.indexContent(
            ENTITY_TYPE,
            ENTITY_TYPE + "_deep_dive_2",
            "Professional diver's watch boasting sapphire crystal protection, ceramic bezel, and chronometer certification.",
            Map.of(
                "brand", "Omega",
                "feature", "sapphire crystal",
                "category", "luxury-watch"
            )
        );

        ragProvider.indexContent(
            ENTITY_TYPE,
            ENTITY_TYPE + "_query_match",
            TARGET_QUERY + " plus helium escape valve and luminous indices",
            Map.of(
                "brand", "Rolex",
                "feature", "sapphire crystal",
                "category", "luxury-watch"
            )
        );
    }
}
