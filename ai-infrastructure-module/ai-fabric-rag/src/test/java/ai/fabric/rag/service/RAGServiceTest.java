package ai.fabric.rag.service;

import ai.fabric.config.AIProviderConfig;
import ai.fabric.core.AIEmbeddingService;
import ai.fabric.core.AISearchService;
import ai.fabric.dto.AIAccessSubjectContext;
import ai.fabric.dto.AIEmbeddingResponse;
import ai.fabric.dto.AISearchRequest;
import ai.fabric.dto.AISearchResponse;
import ai.fabric.dto.RAGRequest;
import ai.fabric.dto.RAGResponse;
import ai.fabric.rag.VectorDatabaseService;
import ai.fabric.spi.RAGProvider;
import ai.fabric.vector.VectorDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for RAGService.
 * 
 * <p>Tests verify that RAGService correctly implements the RAGProvider SPI.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RAGServiceTest {

    private AIProviderConfig config;
    
    @Mock
    private AIEmbeddingService embeddingService;
    
    @Mock
    private VectorDatabaseService vectorDatabaseService;
    
    @Mock
    private VectorDatabase vectorDatabase;
    
    @Mock
    private AISearchService searchService;
    
    private RAGService ragService;
    
    @BeforeEach
    void setUp() {
        config = new AIProviderConfig();
        config.setEmbeddingProvider("openai");

        ragService = new RAGService(
            config,
            embeddingService,
            vectorDatabaseService,
            vectorDatabase,
            searchService,
            null
        );

        AIEmbeddingResponse embeddingResponse = AIEmbeddingResponse.builder()
            .embedding(List.of(0.1, 0.2, 0.3))
            .processingTimeMs(7L)
            .build();
        when(embeddingService.executeEmbedding(any())).thenReturn(
            new AIEmbeddingService.EmbeddingExecution(
                embeddingResponse,
                false,
                "openai",
                "text-embedding-3-small",
                7L,
                7L
            )
        );
        
        when(searchService.search(any(), any())).thenReturn(
            AISearchResponse.builder()
                .results(Collections.emptyList())
                .totalResults(0)
                .build()
        );
    }
    
    @Test
    @DisplayName("RAGService implements RAGProvider interface")
    void ragServiceImplementsRAGProvider() {
        assertThat(ragService).isInstanceOf(RAGProvider.class);
    }
    
    @Test
    @DisplayName("getProviderName returns expected name")
    void getProviderNameReturnsExpectedName() {
        assertThat(ragService.getProviderName()).isEqualTo("default-rag-service");
    }
    
    @Test
    @DisplayName("isAvailable returns true by default")
    void isAvailableReturnsTrueByDefault() {
        assertThat(ragService.isAvailable()).isTrue();
    }
    
    @Test
    @DisplayName("performRag returns successful response")
    void performRagReturnsSuccessfulResponse() {
        RAGRequest request = RAGRequest.builder()
            .query("test query")
            .entityType("document")
            .limit(10)
            .threshold(0.7)
            .build();
        
        RAGResponse response = ragService.performRag(request);
        
        assertThat(response).isNotNull();
        assertThat(response.getSuccess()).isTrue();
        assertThat(response.getDocuments()).isNotNull();
    }
    
    @Test
    @DisplayName("performRAGQuery returns successful response")
    void performRAGQueryReturnsSuccessfulResponse() {
        when(vectorDatabaseService.hybridSearch(any(), anyString(), any())).thenReturn(
            AISearchResponse.builder()
                .results(Collections.emptyList())
                .totalResults(0)
                .build()
        );
        
        RAGRequest request = RAGRequest.builder()
            .query("test query")
            .entityType("document")
            .limit(10)
            .threshold(0.7)
            .build();
        
        RAGResponse response = ragService.performRAGQuery(request);
        
        assertThat(response).isNotNull();
        assertThat(response.getSuccess()).isTrue();
    }

    @Test
    @DisplayName("performRag returns a failed response for null request")
    void performRagReturnsFailedResponseForNullRequest() {
        RAGResponse response = ragService.performRag(null);

        assertThat(response.getSuccess()).isFalse();
        assertThat(response.getDocuments()).isEmpty();
        assertThat(response.getErrorMessage()).contains("must not be null");
    }

    @Test
    @DisplayName("performRAGQuery tolerates string scores and null totals")
    void performRAGQueryToleratesStringScoresAndNullTotals() {
        when(vectorDatabaseService.hybridSearch(any(), anyString(), any())).thenReturn(
            AISearchResponse.builder()
                .results(List.of(Map.of(
                    "id", 123,
                    "content", "Document with string scores",
                    "score", "0.82",
                    "similarity", "0.76",
                    "metadata", "{\"knowledgeSourceId\":\"manual\"}"
                )))
                .totalResults(null)
                .build()
        );

        RAGResponse response = ragService.performRAGQuery(RAGRequest.builder()
            .query("string scores")
            .entityType("document")
            .limit(null)
            .threshold(null)
            .enableHybridSearch(true)
            .build());

        assertThat(response.getSuccess()).isTrue();
        assertThat(response.getTotalDocuments()).isEqualTo(1);
        assertThat(response.getUsedDocuments()).isEqualTo(1);
        assertThat(response.getDocuments()).hasSize(1);
        assertThat(response.getDocuments().get(0).getId()).isEqualTo("123");
        assertThat(response.getDocuments().get(0).getScore()).isEqualTo(0.82);
        assertThat(response.getDocuments().get(0).getSimilarity()).isEqualTo(0.76);
        assertThat(response.getDocuments().get(0).getSource()).isEqualTo("manual");
    }

    @Test
    @DisplayName("performRAGQuery filters JSON metadata with collection values before building context")
    void performRAGQueryFiltersJsonMetadataCollectionValuesBeforeBuildingContext() {
        when(vectorDatabaseService.hybridSearch(any(), anyString(), any())).thenReturn(
            AISearchResponse.builder()
                .results(List.of(
                    Map.of(
                        "id", "refund-policy",
                        "content", "Refund requests can be opened from the billing portal.",
                        "score", 0.92,
                        "similarity", 0.89,
                        "metadata", "{\"tags\":[\"billing\",\"refund\"],\"audience\":\"customer\"}"
                    ),
                    Map.of(
                        "id", "password-reset",
                        "content", "Password resets are handled by account security.",
                        "score", 0.95,
                        "similarity", 0.93,
                        "metadata", Map.of("tags", List.of("identity", "security"))
                    )
                ))
                .totalResults(2)
                .build()
        );

        RAGResponse response = ragService.performRAGQuery(RAGRequest.builder()
            .query("refund help")
            .entityType("article")
            .limit(5)
            .filters(Map.of("tags", "refund"))
            .build());

        assertThat(response.getSuccess()).isTrue();
        assertThat(response.getDocuments())
            .singleElement()
            .satisfies(document -> {
                assertThat(document.getId()).isEqualTo("refund-policy");
                assertThat(document.getMetadata()).containsEntry("audience", "customer");
                assertThat(document.getMetadata().get("tags")).asList().containsExactly("billing", "refund");
            });
        assertThat(response.getUsedDocuments()).isEqualTo(1);
        assertThat(response.getContext())
            .contains("Refund requests can be opened")
            .doesNotContain("Password resets");
    }

    @Test
    @DisplayName("performRAGQuery enforces trusted tenant and deployment vector filters")
    void performRAGQueryEnforcesTrustedAccessBoundaryFilters() {
        when(vectorDatabaseService.hybridSearch(any(), anyString(), any())).thenReturn(
            AISearchResponse.builder()
                .results(Collections.emptyList())
                .totalResults(0)
                .build()
        );

        RAGResponse response = ragService.performRAGQuery(RAGRequest.builder()
            .query("deployment status")
            .entityType("deployment-knowledge")
            .enableHybridSearch(true)
            .filters(Map.of(
                "sourceType", "runbook",
                "tenantId", "spoofed-tenant",
                "deploymentId", "spoofed-deployment"
            ))
            .authContext(AIAccessSubjectContext.builder()
                .subjectId("operator-1")
                .tenantId("trusted-tenant")
                .deploymentId("trusted-deployment")
                .build())
            .build());

        assertThat(response.getSuccess()).isTrue();
        ArgumentCaptor<AISearchRequest> requestCaptor =
            ArgumentCaptor.forClass(AISearchRequest.class);
        verify(vectorDatabaseService).hybridSearch(
            any(),
            anyString(),
            requestCaptor.capture()
        );
        assertThat(requestCaptor.getValue().getMetadata())
            .containsEntry("sourceType", "runbook")
            .containsEntry("tenantId", "trusted-tenant")
            .containsEntry("deploymentId", "trusted-deployment");
    }
    
    @Test
    @DisplayName("getStatistics returns non-null map")
    void getStatisticsReturnsNonNullMap() {
        when(vectorDatabaseService.getStatistics()).thenReturn(Map.of("count", 100));
        when(vectorDatabase.getStatistics()).thenReturn(Map.of("size", 50));
        
        Map<String, Object> stats = ragService.getStatistics();
        
        assertThat(stats).isNotNull();
        assertThat(stats).containsKey("totalIndexed");
        assertThat(stats).containsKey("vectorDatabase");
    }
    
}
