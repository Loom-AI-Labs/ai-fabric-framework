package ai.fabric.it;

import ai.fabric.core.AICoreService;
import ai.fabric.dto.AdvancedRAGRequest;
import ai.fabric.dto.AdvancedRAGResponse;
import ai.fabric.dto.AdvancedRAGResponse.RAGDocument;
import ai.fabric.rag.service.AdvancedRAGService;
import ai.fabric.spi.RAGProvider;
import ai.fabric.service.VectorManagementService;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Integration test for TEST-RAG-010: Cross-Entity RAG Queries.
 */
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("dev")
@TestPropertySource(properties = {
    "ai.vector-db.lucene.index-path=./data/test-lucene-index/cross-entity-rag",
    "ai.vector-db.lucene.similarity-threshold=0.3"
})
class RAGCrossEntityQueryIntegrationTest {

    private static final String PRODUCT_ENTITY = "ragcrossentityproduct";
    private static final String CUSTOMER_ENTITY = "ragcrossentitycustomer";
    private static final String ORDER_ENTITY = "ragcrossentityorder";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Autowired
    private RAGProvider ragProvider;

    @Autowired
    private AdvancedRAGService advancedRAGService;

    @Autowired
    private VectorManagementService vectorManagementService;

    @MockBean
    private AICoreService aiCoreService;

    @BeforeEach
    void setUp() {
        when(aiCoreService.generateText(anyString())).thenAnswer(invocation -> {
            String prompt = invocation.getArgument(0);
            if (prompt.contains("Generate") && prompt.contains("related queries")) {
                return String.join("\n",
                    "High value customer purchases last month",
                    "VIP customer order summaries last month",
                    "Premium loyalty customers bought recently"
                );
            }
            if (prompt.contains("Optimize this context")) {
                return "Condensed cross-entity insight highlighting VIP purchases.";
            }
            return "High-value customers Ava Stone and Liam Chen purchased Orion X carbon road bikes and Lumen Platinum smartwatches last month.";
        });

        clearEntityIndexes();
        seedCrossEntityData();
    }

    @AfterEach
    void tearDown() {
        clearEntityIndexes();
    }

    @Test
    @DisplayName("Cross-entity RAG aggregates customer, order, and product context")
    void crossEntityQueryAggregatesMultipleEntityTypes() {
        AdvancedRAGResponse response = advancedRAGService.performAdvancedRAG(
            AdvancedRAGRequest.builder()
                .query("What products did high-value customers purchase last month?")
                .maxResults(12)
                .maxDocuments(6)
                .expansionLevel(3)
                .enableHybridSearch(true)
                .enableContextualSearch(true)
                .contextOptimizationLevel("medium")
                .metadata(Map.of("entityTypes", List.of("product", "customer", "order")))
                .similarityThreshold(0.0)
                .build()
        );

        assertNotNull(response, "Advanced RAG response should not be null");
        assertTrue(Boolean.TRUE.equals(response.getSuccess()), "Advanced RAG call should succeed");

        List<RAGDocument> documents = Optional.ofNullable(response.getDocuments()).orElse(List.of());
        assertFalse(documents.isEmpty(), "Cross-entity query should surface documents");

        String combinedContent = documents.stream()
            .map(RAGDocument::getContent)
            .collect(Collectors.joining(" "))
            .toLowerCase();

        Map<String, Long> documentsByType = documents.stream()
            .map(this::resolveEntityCategory)
            .flatMap(Optional::stream)
            .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));

        assertTrue(documentsByType.getOrDefault("customer", 0L) > 0, "Results should contain customer insights");
        assertTrue(documentsByType.getOrDefault("order", 0L) > 0, "Results should contain order summaries");
        assertTrue(documentsByType.getOrDefault("product", 0L) > 0, "Results should contain product briefs");

        assertTrue(combinedContent.contains("ava stone"), "Combined document content should include VIP customer detail");
        assertTrue(combinedContent.contains("liam chen"), "Combined document content should reference multiple high-value customers");
        assertTrue(combinedContent.contains("orion x carbon road bike"), "Combined content should highlight purchased products");
        assertTrue(combinedContent.contains("september"), "Combined content should preserve time reference from orders");

        Set<String> relationshipSummaries = documents.stream()
            .map(this::ensureMetadata)
            .map(metadata -> metadata.get("relationshipSummary"))
            .filter(Objects::nonNull)
            .map(Object::toString)
            .map(String::toLowerCase)
            .collect(Collectors.toSet());

        assertTrue(relationshipSummaries.stream().anyMatch(summary -> summary.contains("ava stone")),
            "Metadata should retain relationships for Ava Stone");
        assertTrue(relationshipSummaries.stream().anyMatch(summary -> summary.contains("liam chen")),
            "Metadata should retain relationships for Liam Chen");

        List<String> expandedQueries = Optional.ofNullable(response.getExpandedQueries()).orElse(List.of());
        assertTrue(expandedQueries.size() >= 2, "Query expansion should introduce related phrasing");
    }

    private void seedCrossEntityData() {
        ragProvider.indexContent(
            CUSTOMER_ENTITY,
            "cust_ava_stone",
            "Profile: Ava Stone is a high-value cycling enthusiast. Last month she invested $8,900 in an Orion X carbon road bike bundle and a Lumen Platinum smartwatch to track performance.",
            Map.of(
                "recordType", "customer",
                "customerTier", "high-value",
                "segment", "vip",
                "month", "September",
                "relationshipSummary", "Ava Stone purchased an Orion X carbon road bike bundle and Lumen Platinum smartwatch last month."
            )
        );

        ragProvider.indexContent(
            CUSTOMER_ENTITY,
            "cust_liam_chen",
            "Profile: Liam Chen is a high-value triathlete who refreshes premium gear quarterly. Last month he secured the Orion X carbon road bike and the AeroFlow racing kit for upcoming events.",
            Map.of(
                "recordType", "customer",
                "customerTier", "high-value",
                "segment", "vip",
                "month", "September",
                "relationshipSummary", "Liam Chen ordered an Orion X carbon road bike with the AeroFlow racing kit in September."
            )
        );

        ragProvider.indexContent(
            PRODUCT_ENTITY,
            "prod_orion_x",
            "Product brief: Orion X carbon road bike engineered for elite endurance with aerodynamic carbon frame and integrated telemetry suited for high-value athletes.",
            Map.of(
                "recordType", "product",
                "category", "cycling",
                "priceTier", "premium",
                "customerTier", "high-value",
                "relationshipSummary", "Orion X carbon road bike is favored by Ava Stone and Liam Chen for September upgrades."
            )
        );

        ragProvider.indexContent(
            PRODUCT_ENTITY,
            "prod_lumen_platinum",
            "Product brief: Lumen Platinum smartwatch with adaptive coaching and VO2 max analytics bundled with premium bike packages.",
            Map.of(
                "recordType", "product",
                "category", "wearables",
                "priceTier", "premium",
                "customerTier", "high-value",
                "relationshipSummary", "Lumen Platinum smartwatch bundled with Ava Stone's September bike order."
            )
        );

        ragProvider.indexContent(
            ORDER_ENTITY,
            "order_4521",
            "Order summary: Invoice 4521 closed on September 18 for high-value customer Ava Stone including Orion X carbon road bike, Lumen Platinum smartwatch, and bike fitting concierge service.",
            Map.of(
                "recordType", "order",
                "customerId", "cust_ava_stone",
                "customerTier", "high-value",
                "month", "September",
                "relationshipSummary", "Order 4521 for Ava Stone bundled Orion X carbon road bike and Lumen Platinum smartwatch in September."
            )
        );

        ragProvider.indexContent(
            ORDER_ENTITY,
            "order_4578",
            "Order summary: Invoice 4578 processed on September 22 for VIP customer Liam Chen combining Orion X carbon road bike, AeroFlow racing kit, and extended maintenance coverage.",
            Map.of(
                "recordType", "order",
                "customerId", "cust_liam_chen",
                "customerTier", "high-value",
                "month", "September",
                "relationshipSummary", "Order 4578 for Liam Chen included the Orion X carbon road bike and AeroFlow racing kit in September."
            )
        );

    }

    private void clearEntityIndexes() {
        vectorManagementService.clearAllVectors();
    }

    private Optional<String> resolveEntityCategory(RAGDocument document) {
        Map<String, Object> metadata = ensureMetadata(document);
        Object recordType = metadata.getOrDefault("recordType", metadata.get("entityType"));
        return Optional.ofNullable(recordType).map(Object::toString).map(String::toLowerCase);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> ensureMetadata(RAGDocument document) {
        Object metadata = document.getMetadata();
        if (metadata instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        if (metadata instanceof String json) {
            try {
                return OBJECT_MAPPER.readValue(json, Map.class);
            } catch (Exception ignored) {
                return Map.of();
            }
        }
        return Map.of();
    }
}
