package ai.fabric.rag.service;

import ai.fabric.core.AISearchService;
import ai.fabric.dto.AISearchRequest;
import ai.fabric.dto.AISearchResponse;
import ai.fabric.dto.RAGRequest;
import ai.fabric.rag.LexicalSearchUnavailableException;
import ai.fabric.rag.RetrievalCapabilities;
import ai.fabric.rag.RetrievalProfile;
import ai.fabric.rag.VectorDatabaseService;
import ai.fabric.rag.source.ResolvedKnowledgeSource;
import ai.fabric.rag.source.SearchSource;
import ai.fabric.rag.source.SearchSourceRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ProfiledRAGSearchTest {
    private final VectorDatabaseService database = mock(VectorDatabaseService.class);
    private final AISearchService search = mock(AISearchService.class);
    private final AISearchRequest base = AISearchRequest.builder().query("delivery").entityType("document")
        .threshold(.7).limit(6).metadata(Map.of("customerId", "owner")).build();

    @Test
    void performsRealIndependentLegsAndDoesNotApplyDenseThresholdToLexical() {
        when(database.retrievalCapabilities("document")).thenReturn(ready());
        when(search.search(any(), any())).thenReturn(response("dense", .9));
        when(database.keywordSearch(any(), any())).thenAnswer(invocation -> {
            AISearchRequest request = invocation.getArgument(1);
            assertThat(request.getThreshold()).isZero();
            assertThat(request.getMetadata()).containsEntry("customerId", "owner");
            return response("exact", .2);
        });
        var result = execute(profile(RetrievalProfile.FailurePolicy.FAIL));
        assertThat(result.hybridSearchUsed()).isTrue();
        assertThat(result.response().getResults()).hasSize(2);
        assertThat(result.response().getDiagnostics()).containsEntry("effectiveMode", "HYBRID");
        verify(database, never()).hybridSearch(any(), any(), any());
    }

    @Test
    void unsupportedCorpusCannotMasqueradeAsHybrid() {
        when(database.retrievalCapabilities("document")).thenReturn(RetrievalCapabilities.denseOnly("old schema"));
        when(search.search(any(), any())).thenReturn(response("dense", .9));
        var result = execute(profile(RetrievalProfile.FailurePolicy.VISIBLE_DENSE_FALLBACK));
        assertThat(result.hybridSearchUsed()).isFalse();
        assertThat(result.degraded()).isTrue();
        assertThat(result.response().getDiagnostics()).containsEntry("effectiveMode", "DENSE");
        verify(database, never()).keywordSearch(any(), any());
    }

    @Test
    void strictMissingIndexStopsBeforeRetrieval() {
        when(database.retrievalCapabilities("document")).thenReturn(RetrievalCapabilities.denseOnly("old schema"));
        assertThatThrownBy(() -> execute(profile(RetrievalProfile.FailurePolicy.FAIL)))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("LEXICAL_INDEX_NOT_READY");
        verifyNoInteractions(search);
    }

    @Test
    void onlyTypedAvailabilityFailureMayDegradeNeverAuthorizationOrBudget() {
        when(database.retrievalCapabilities("document")).thenReturn(ready());
        when(search.search(any(), any())).thenReturn(response("dense", .9));
        when(database.keywordSearch(any(), any())).thenThrow(new LexicalSearchUnavailableException("timeout"));
        assertThat(execute(profile(RetrievalProfile.FailurePolicy.VISIBLE_DENSE_FALLBACK)).degraded()).isTrue();
        doThrow(new SecurityException("access denied")).when(database).keywordSearch(any(), any());
        assertThatThrownBy(() -> execute(profile(RetrievalProfile.FailurePolicy.VISIBLE_DENSE_FALLBACK)))
            .isInstanceOf(SecurityException.class);
    }

    @Test
    void sourceAdapterReceivesTrustedScopeAndOneRequestWideCandidateAllowance() {
        SearchSourceRegistry registry = mock(SearchSourceRegistry.class);
        SearchSource first = source("a");
        SearchSource second = source("b");
        SearchSource denied = source("foreign");
        when(denied.isEligible(any())).thenReturn(false);
        when(registry.resolveSearchSources(any())).thenReturn(List.of(first, second, denied));
        var request = RAGRequest.builder().query("delivery").entityType("document")
            .retrievalProfile(profile(RetrievalProfile.FailurePolicy.FAIL)).build();
        var result = new RAGSearchExecutor(database, search, registry).performSearch(List.of(1.0), request, base, false);
        assertThat(result.skippedSourceCount()).isEqualTo(1);
        for (SearchSource source : List.of(first, second)) {
            verify(source).search(any(), argThat(r -> !r.getEnableHybridSearch()),
                argThat(r -> r.getLimit() == 10 && "owner".equals(r.getMetadata().get("customerId"))));
            verify(source).keywordSearch(eq(request), argThat(r -> r.getLimit() == 10));
        }
        verify(denied, never()).search(any(), any(), any());
        verify(denied, never()).keywordSearch(any(), any());
        verifyNoInteractions(database, search);
    }

    private SearchSource source(String id) {
        SearchSource source = mock(SearchSource.class);
        when(source.sourceId()).thenReturn(id);
        when(source.source()).thenReturn(ResolvedKnowledgeSource.builder().id(id).entityType("document").build());
        when(source.isEligible(any())).thenReturn(true);
        when(source.retrievalCapabilities(any())).thenReturn(ready());
        when(source.search(any(), any(), any())).thenReturn(response(id, .9));
        when(source.keywordSearch(any(), any())).thenReturn(response(id, 2));
        return source;
    }

    @Test
    void mixedSpacesShareCandidateBudgetFusionAndExactlyOneRerankingCall() {
        var registry = mock(SearchSourceRegistry.class);
        var documents = source("policies");
        var entities = source("products");
        when(registry.resolveSearchSources(any())).thenAnswer(call -> {
            RAGRequest scope = call.getArgument(0);
            assertThat(scope.getRetrievalEntityTypes()).isNull();
            return "document".equals(scope.getEntityType()) ? List.of(documents) : List.of(entities);
        });
        when(documents.search(any(), any(), any())).thenReturn(response("same-id", .9));
        when(entities.search(any(), any(), any())).thenReturn(response("same-id", .8));
        when(documents.keywordSearch(any(), any())).thenReturn(response("same-id", 3));
        when(entities.keywordSearch(any(), any())).thenReturn(response("same-id", 2));
        var reranker = mock(RerankingStage.class);
        when(reranker.rank(any(), any(), anyLong())).thenAnswer(call ->
            new RerankingStage.Ranked(call.getArgument(1), Map.of("used", true)));
        var request = RAGRequest.builder().query("delivery").entityType("document")
            .retrievalEntityTypes(List.of("document", "product"))
            .retrievalProfile(profile(RetrievalProfile.FailurePolicy.FAIL)).build();
        var result = new RAGSearchExecutor(database, search, registry, reranker)
            .performSearch(List.of(1.0), request, base, false);
        assertThat(result.response().getResults()).hasSize(2);
        assertThat(result.response().getDiagnostics()).containsEntry("selectedEntityTypes", List.of("document", "product"));
        assertThat(result.response().getResults()).extracting(row -> row.get("entityType")).containsExactlyInAnyOrder("document", "product");
        verify(reranker).rank(eq("delivery"), argThat(rows -> rows.size() == 2), anyLong());
        verify(documents).search(any(), argThat(r -> "document".equals(r.getEntityType())), argThat(r -> r.getLimit() == 10));
        verify(entities).keywordSearch(argThat(r -> "product".equals(r.getEntityType())),
            argThat(r -> r.getLimit() == 10 && "owner".equals(r.getMetadata().get("customerId"))));
        verifyNoInteractions(database, search);
    }

    @Test
    void invalidSharedScopeStopsBeforeAnyNativeRequest() {
        var request = RAGRequest.builder().query("delivery").entityType("document")
            .retrievalEntityTypes(List.of("document", "document"))
            .retrievalProfile(profile(RetrievalProfile.FailurePolicy.FAIL)).build();
        assertThatThrownBy(() -> new RAGSearchExecutor(database, search, null)
            .performSearch(List.of(1.0), request, base, false)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(database, search);
    }

    @Test
    void wrongSpaceEvidenceCannotReachAnExternalReranker() {
        when(database.retrievalCapabilities("document")).thenReturn(ready());
        when(search.search(any(), any())).thenReturn(AISearchResponse.builder()
            .results(List.of(Map.of("id", "foreign", "entityType", "private-record", "content", "must not be shared"))).build());
        when(database.keywordSearch(any(), any())).thenReturn(AISearchResponse.builder().results(List.of()).build());
        var reranker = mock(RerankingStage.class);
        var request = RAGRequest.builder().entityType("document").query("delivery")
            .retrievalProfile(profile(RetrievalProfile.FailurePolicy.FAIL)).build();
        assertThatThrownBy(() -> new RAGSearchExecutor(database, search, null, reranker)
            .performSearch(List.of(1.0), request, base, false)).isInstanceOf(SecurityException.class);
        verifyNoInteractions(reranker);
    }

    @Test void rerankingFallbackIsVisibleWithoutFalselyReportingIncompleteHybridLegs() {
        when(database.retrievalCapabilities("document")).thenReturn(ready());
        when(search.search(any(), any())).thenReturn(response("dense", .9));
        when(database.keywordSearch(any(), any())).thenReturn(response("exact", .3));
        var reranker = mock(RerankingStage.class);
        when(reranker.rank(any(), any(), anyLong())).thenAnswer(call -> new RerankingStage.Ranked(call.getArgument(1),
            Map.of("used", false, "status", "PRE_RERANK_FALLBACK", "reason", "TRANSIENT_HTTP")));
        var request = RAGRequest.builder().entityType("document").query("delivery")
            .retrievalProfile(profile(RetrievalProfile.FailurePolicy.FAIL)).build();
        var result = new RAGSearchExecutor(database, search, null, reranker).performSearch(List.of(1.0), request, base, false);
        assertThat(result.degraded()).isTrue();
        assertThat(result.response().getDiagnostics()).containsEntry("degraded", true).containsEntry("effectiveMode", "HYBRID");
        assertThat(result.response().getResults()).hasSize(2);
    }

    private RAGSearchExecutor.SearchExecutionAggregate execute(RetrievalProfile profile) {
        return new RAGSearchExecutor(database, search, null).performSearch(List.of(1.0),
            RAGRequest.builder().query("delivery").retrievalProfile(profile).build(), base, true);
    }

    private static RetrievalCapabilities ready() { return new RetrievalCapabilities(true, true, "BM25", "g1", ""); }
    private static RetrievalProfile profile(RetrievalProfile.FailurePolicy policy) {
        return new RetrievalProfile(RetrievalProfile.Mode.HYBRID, 20, 20, 40, 6, 60, policy);
    }
    private static AISearchResponse response(String id, double score) {
        return AISearchResponse.builder().results(List.of(Map.of("id", id, "content", id, "score", score))).build();
    }
}
