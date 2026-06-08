package com.ai.infrastructure.relationship.service;

import com.ai.infrastructure.core.AIEmbeddingService;
import com.ai.infrastructure.dto.AIEmbeddingRequest;
import com.ai.infrastructure.dto.AIEmbeddingResponse;
import com.ai.infrastructure.dto.AISearchResponse;
import com.ai.infrastructure.dto.RAGResponse;
import com.ai.infrastructure.relationship.cache.QueryCache;
import com.ai.infrastructure.relationship.config.RelationshipModuleMetadata;
import com.ai.infrastructure.relationship.config.RelationshipQueryProperties;
import com.ai.infrastructure.relationship.dto.JpqlQuery;
import com.ai.infrastructure.relationship.dto.RelationshipQueryPlan;
import com.ai.infrastructure.relationship.model.QueryOptions;
import com.ai.infrastructure.relationship.model.ReturnMode;
import com.ai.infrastructure.relationship.validation.RelationshipQueryValidator;
import com.ai.infrastructure.relationship.metrics.QueryMetrics;
import com.ai.infrastructure.rag.VectorDatabaseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LLMDrivenJPAQueryServiceTest {

    @Mock
    private RelationshipQueryPlanner planner;
    @Mock
    private DynamicJPAQueryBuilder queryBuilder;
    @Mock
    private RelationshipQueryValidator validator;
    @Mock
    private RelationshipTraversalService jpaTraversalService;
    @Mock
    private RelationshipQueryDocumentMapper documentMapper;
    @Mock
    private VectorDatabaseService vectorDatabaseService;
    @Mock
    private AIEmbeddingService embeddingService;
    @Mock
    private QueryCache queryCache;
    @Mock
    private QueryMetrics queryMetrics;

    private RelationshipQueryProperties properties;
    private RelationshipModuleMetadata moduleMetadata;
    private LLMDrivenJPAQueryService service;

    @BeforeEach
    void setUp() {
        properties = new RelationshipQueryProperties();
        properties.setDefaultReturnMode(ReturnMode.IDS);
        properties.setEnableVectorSearch(true);
        moduleMetadata = RelationshipModuleMetadata.from(properties);

        service = new LLMDrivenJPAQueryService(
            planner,
            queryBuilder,
            validator,
            properties,
            moduleMetadata,
            jpaTraversalService,
            documentMapper,
            vectorDatabaseService,
            embeddingService,
            queryCache,
            queryMetrics
        );

    }

    @Test
    void shouldMaterializeDocumentsFromJpaResults() {
        RelationshipQueryPlan plan = basePlan();
        JpqlQuery jpqlQuery = defaultQuery();

        when(planner.planQuery(eq("find documents"), anyList())).thenReturn(plan);
        when(queryBuilder.buildQuery(plan)).thenReturn(jpqlQuery);
        Object entity = new Object();
        when(jpaTraversalService.traverse(plan, jpqlQuery))
            .thenReturn(TraversalResult.entities(List.of(entity), List.of("doc-1")));
        when(documentMapper.map(eq("document"), eq(entity), eq("doc-1")))
            .thenReturn(Optional.of(RAGResponse.RAGDocument.builder()
                .id("doc-1")
                .content("Finance doc")
                .metadata(Map.of("status", "active"))
                .build()));

        QueryOptions options = QueryOptions.builder()
            .returnMode(ReturnMode.FULL)
            .limit(5)
            .build();

        RAGResponse response = service.executeRelationshipQuery("find documents", List.of("document"), options);

        assertThat(response.getDocuments()).hasSize(1);
        assertThat(response.getDocuments().get(0).getMetadata()).containsEntry("status", "active");
        assertThat(response.getEntityType()).isEqualTo("document");
    }

    @Test
    void shouldUseCachedJpaResultWhenAvailable() {
        RelationshipQueryPlan plan = basePlan();
        JpqlQuery jpqlQuery = defaultQuery();

        when(queryCache.isEnabled()).thenReturn(true);
        when(planner.planQuery(eq("cached query"), anyList())).thenReturn(plan);
        when(queryBuilder.buildQuery(plan)).thenReturn(jpqlQuery);
        when(queryCache.getQueryResult(anyString())).thenReturn(Optional.of(List.of("cached-1")));

        RAGResponse response = service.executeRelationshipQuery("cached query", List.of("document"), QueryOptions.defaults());

        assertThat(response.getDocuments()).extracting(RAGResponse.RAGDocument::getId).containsExactly("cached-1");
        verify(jpaTraversalService, never()).traverse(any(), any());
        verify(queryCache, never()).putQueryResult(anyString(), any());
    }

    @Test
    void shouldReturnEmptyWhenJpaReturnsEmpty() {
        RelationshipQueryPlan plan = basePlan();
        JpqlQuery jpqlQuery = defaultQuery();

        when(planner.planQuery(eq("needs fallback"), anyList())).thenReturn(plan);
        when(queryBuilder.buildQuery(plan)).thenReturn(jpqlQuery);
        when(jpaTraversalService.traverse(plan, jpqlQuery)).thenReturn(TraversalResult.empty());

        RAGResponse response = service.executeRelationshipQuery("needs fallback", List.of("document"), QueryOptions.defaults());

        assertThat(response.getDocuments()).isEmpty();
    }

    @Test
    void shouldRerankWithVectorsWhenPlanRequiresSemanticSearch() {
        when(queryCache.isEnabled()).thenReturn(true);
        when(queryCache.getEmbedding(anyString())).thenReturn(Optional.empty());

        RelationshipQueryPlan plan = RelationshipQueryPlan.builder()
            .primaryEntityType("document")
            .originalQuery("vector query")
            .semanticQuery("customer churn trends")
            .needsSemanticSearch(true)
            .build();
        JpqlQuery jpqlQuery = defaultQuery();

        when(planner.planQuery(eq("vector query"), anyList())).thenReturn(plan);
        when(queryBuilder.buildQuery(plan)).thenReturn(jpqlQuery);
        Object docEntity = new Object();
        Object vecEntity = new Object();
        when(jpaTraversalService.traverse(plan, jpqlQuery))
            .thenReturn(TraversalResult.entities(List.of(docEntity, vecEntity), List.of("doc-1", "vec-1")));

        AIEmbeddingResponse embeddingResponse = AIEmbeddingResponse.builder()
            .embedding(List.of(0.1, 0.2, 0.3))
            .build();
        when(embeddingService.generateEmbedding(any(AIEmbeddingRequest.class))).thenReturn(embeddingResponse);

        AISearchResponse vectorResponse = AISearchResponse.builder()
            .results(List.of(Map.of("entityId", "vec-1")))
            .build();
        when(vectorDatabaseService.searchByEntityType(eq(embeddingResponse.getEmbedding()), eq("document"), anyInt(), anyDouble()))
            .thenReturn(vectorResponse);

        when(documentMapper.map(eq("document"), eq(docEntity), eq("doc-1")))
            .thenReturn(Optional.of(RAGResponse.RAGDocument.builder().id("doc-1").content("JPA doc").build()));
        when(documentMapper.map(eq("document"), eq(vecEntity), eq("vec-1")))
            .thenReturn(Optional.of(RAGResponse.RAGDocument.builder().id("vec-1").content("Vector doc").build()));

        QueryOptions options = QueryOptions.builder()
            .returnMode(ReturnMode.FULL)
            .limit(5)
            .build();

        RAGResponse response = service.executeRelationshipQuery("vector query", List.of("document"), options);

        assertThat(response.getHybridSearchUsed()).isTrue();
        assertThat(response.getDocuments()).extracting(RAGResponse.RAGDocument::getId)
            .containsExactly("vec-1", "doc-1");
        verify(embeddingService).generateEmbedding(any(AIEmbeddingRequest.class));
        verify(vectorDatabaseService).searchByEntityType(eq(embeddingResponse.getEmbedding()), eq("document"), eq(5), anyDouble());
        verify(queryCache).putEmbedding(anyString(), eq(embeddingResponse));
    }

    @Test
    void shouldRecordExecutionFailureWhenRuntimeExceptionThrown() {
        when(queryMetrics.isEnabled()).thenReturn(true);
        when(planner.planQuery(eq("boom"), anyList())).thenThrow(new IllegalStateException("planner down"));

        assertThatThrownBy(() -> service.executeRelationshipQuery("boom", List.of("document"), QueryOptions.defaults()))
            .isInstanceOf(IllegalStateException.class);

        verify(queryMetrics).recordExecutionFailure(anyLong());
    }

    private RelationshipQueryPlan basePlan() {
        return RelationshipQueryPlan.builder()
            .primaryEntityType("document")
            .originalQuery("base")
            .build();
    }

    private JpqlQuery defaultQuery() {
        return JpqlQuery.builder()
            .jpql("select d from Document d")
            .build();
    }

}
