package ai.fabric.rag.service;

import ai.fabric.core.AISearchService;
import ai.fabric.dto.AISearchRequest;
import ai.fabric.dto.AISearchResponse;
import ai.fabric.dto.RAGRequest;
import ai.fabric.rag.LexicalSearchUnavailableException;
import ai.fabric.rag.RetrievalCapabilities;
import ai.fabric.rag.RetrievalProfile;
import ai.fabric.rag.VectorDatabaseService;
import ai.fabric.rag.source.SearchSource;
import ai.fabric.rag.source.SearchSourceRegistry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ProfiledRAGSearch {
    private ProfiledRAGSearch() { }

    static RAGSearchExecutor.SearchExecutionAggregate execute(List<Double> vector, RAGRequest request,
            AISearchRequest base, VectorDatabaseService database, AISearchService search,
            SearchSourceRegistry registry, RerankingStage reranker) {
        RetrievalProfile profile = request.getRetrievalProfile();
        List<RAGRequest> scopes = scopedRequests(request, base.getEntityType());
        List<SearchTarget> targets = new ArrayList<>();
        int resolvedCount = 0;
        int skipped = 0;
        for (RAGRequest scope : scopes) {
            if (registry == null) {
                targets.add(new SearchTarget(null, scope, baseForType(base, scope.getEntityType())));
                continue;
            }
            List<SearchSource> sources = registry.resolveSearchSources(scope);
            if (sources == null) continue;
            resolvedCount += sources.size();
            for (SearchSource source : sources) {
                if (!source.isEligible(scope)) { skipped++; continue; }
                targets.add(new SearchTarget(source, scope, baseForType(base, scope.getEntityType())));
            }
        }
        List<ReciprocalRankFusion.RankedList> lists = new ArrayList<>();
        List<Map<String, Object>> diagnostics = new ArrayList<>();
        long started = System.currentTimeMillis();
        int eligible = targets.size();
        boolean degraded = false;
        boolean lexicalRan = false;
        long timeoutMs = request.getTimeoutMs() == null ? 30_000L : request.getTimeoutMs();
        if (timeoutMs < 1 || timeoutMs > 120_000L) throw new IllegalArgumentException("Retrieval deadline must be 1-120000ms");
        long deadline = System.nanoTime() + timeoutMs * 1_000_000;
        if ((profile.mode() != RetrievalProfile.Mode.KEYWORD && eligible > profile.vectorCandidates())
            || (profile.mode() != RetrievalProfile.Mode.DENSE && eligible > profile.lexicalCandidates())) {
            throw new IllegalArgumentException("Candidate allowance must cover every selected eligible source");
        }
        for (int index = 0; index < targets.size(); index++) {
            SearchTarget target = targets.get(index);
            SearchSource source = target.source();
            RAGRequest scope = target.request();
            AISearchRequest scopedBase = target.base();
            int vectorLimit = allocation(profile.vectorCandidates(), eligible, index);
            int lexicalLimit = allocation(profile.lexicalCandidates(), eligible, index);
            RetrievalCapabilities capabilities = source == null
                ? database.retrievalCapabilities(scope.getEntityType()) : source.retrievalCapabilities(scope);
            RAGRequest denseRequest = scope.toBuilder().enableHybridSearch(false).build();
            LegResult result = retrieve(profile, capabilities,
                () -> withinDeadline(deadline, () -> source == null
                    ? search.search(vector, legRequest(scopedBase, vectorLimit, false))
                    : source.search(vector, denseRequest, legRequest(scopedBase, vectorLimit, false))),
                () -> withinDeadline(deadline, () -> source == null
                    ? database.keywordSearch(scope.getQuery(), legRequest(scopedBase, lexicalLimit, true))
                    : source.keywordSearch(scope, legRequest(scopedBase, lexicalLimit, true))));
            String sourceId = source == null ? "" : source.sourceId();
            add(lists, sourceId, scope.getEntityType(), result);
            Map<String, Object> diagnostic = new LinkedHashMap<>(result.diagnostic(sourceId, capabilities));
            diagnostic.put("entityType", scope.getEntityType());
            diagnostics.add(Map.copyOf(diagnostic));
            degraded |= result.degraded();
            lexicalRan |= result.lexicalRan();
        }
        List<Map<String, Object>> candidates = profile.mode() == RetrievalProfile.Mode.HYBRID && lexicalRan
            ? ReciprocalRankFusion.fuse(lists, profile.rankConstant(), profile.maxCandidates())
            : ReciprocalRankFusion.rankSingleLeg(lists, profile.maxCandidates());
        Map<String, Object> rerankingDiagnostics = Map.of("used", false, "status",
            Boolean.FALSE.equals(request.getRerankingEnabled()) ? "REQUEST_DISABLED" : "OFF");
        if (reranker != null && !Boolean.FALSE.equals(request.getRerankingEnabled())) {
            var ranked = reranker.rank(request.getQuery(), candidates, deadline);
            candidates = ranked.candidates();
            rerankingDiagnostics = ranked.diagnostics();
        }
        boolean anyDegradation = degraded || "PRE_RERANK_FALLBACK".equals(rerankingDiagnostics.get("status"));
        int limit = Math.min(profile.maxResults(), base.getLimit() == null ? profile.maxResults() : base.getLimit());
        List<Map<String, Object>> finalResults = candidates.stream().limit(limit).toList();
        String effectiveMode = eligible == 0 ? "NONE" : lexicalRan && degraded ? "PARTIAL_HYBRID" : lexicalRan
            ? (profile.mode() == RetrievalProfile.Mode.KEYWORD ? "KEYWORD" : "HYBRID") : "DENSE";
        Map<String, Object> responseDiagnostics = Map.of("requestedMode", profile.mode().name(), "reranking", rerankingDiagnostics,
            "effectiveMode", effectiveMode, "degraded", anyDegradation, "fusionOwner", "framework",
            "selectedEntityTypes", scopes.stream().map(RAGRequest::getEntityType).toList(),
            "candidateCount", candidates.size(), "scoreType", profile.mode() == RetrievalProfile.Mode.HYBRID && lexicalRan
                ? "RRF" : (lexicalRan ? "BM25" : "VECTOR"));
        AISearchResponse response = AISearchResponse.builder().results(finalResults).totalResults(finalResults.size())
            .query(base.getQuery()).requestId(request.getRequestId()).diagnostics(responseDiagnostics)
            .processingTimeMs(System.currentTimeMillis() - started)
            .maxScore(finalResults.isEmpty() ? null : ((Number) finalResults.getFirst().get("score")).doubleValue()).build();
        if (registry != null) registry.recordSearchExecution(diagnostics, anyDegradation);
        return new RAGSearchExecutor.SearchExecutionAggregate(response, List.copyOf(diagnostics), resolvedCount,
            eligible, eligible, eligible, 0, skipped, anyDegradation, "shared_profiled_retrieval", effectiveMode,
            profile.mode() == RetrievalProfile.Mode.HYBRID,
            profile.mode() == RetrievalProfile.Mode.HYBRID && lexicalRan, false, lexicalRan);
    }

    private record SearchTarget(SearchSource source, RAGRequest request, AISearchRequest base) { }

    private static List<RAGRequest> scopedRequests(RAGRequest request, String defaultType) {
        List<String> types = request.getRetrievalEntityTypes();
        if (types == null || types.isEmpty()) {
            return List.of(request.getEntityType() == null ? request.toBuilder().entityType(defaultType).build() : request);
        }
        if (types.size() > 32 || types.stream().anyMatch(type -> type == null || type.isBlank() || type.length() > 256)
            || new java.util.HashSet<>(types).size() != types.size()) {
            throw new IllegalArgumentException("Selected retrieval spaces must be unique, nonblank and bounded to 32");
        }
        return types.stream().map(type -> request.toBuilder().entityType(type).retrievalEntityTypes(null).build()).toList();
    }

    private static AISearchRequest baseForType(AISearchRequest base, String entityType) {
        return AISearchRequest.builder().query(base.getQuery()).entityType(entityType).limit(base.getLimit())
            .threshold(base.getThreshold()).filters(base.getFilters()).metadata(base.getMetadata())
            .sortBy(base.getSortBy()).context(base.getContext()).build();
    }

    private static AISearchResponse withinDeadline(long deadline, java.util.function.Supplier<AISearchResponse> operation) {
        if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
        if (System.nanoTime() >= deadline) throw new IllegalStateException("Retrieval request deadline exhausted");
        AISearchResponse response = operation.get();
        if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
        if (System.nanoTime() >= deadline) throw new IllegalStateException("Retrieval request deadline exhausted");
        return response;
    }

    private static int allocation(int allowance, int sourceCount, int index) {
        return sourceCount == 0 ? allowance : allowance / sourceCount + (index < allowance % sourceCount ? 1 : 0);
    }

    private static LegResult retrieve(RetrievalProfile profile, RetrievalCapabilities capabilities,
                                      java.util.function.Supplier<AISearchResponse> dense,
                                      java.util.function.Supplier<AISearchResponse> lexical) {
        boolean wantsLexical = profile.mode() != RetrievalProfile.Mode.DENSE;
        boolean canDegrade = profile.mode() == RetrievalProfile.Mode.HYBRID
            && profile.failurePolicy() == RetrievalProfile.FailurePolicy.VISIBLE_DENSE_FALLBACK;
        if (wantsLexical && !capabilities.lexicalReady() && !canDegrade) {
            throw new IllegalStateException("LEXICAL_INDEX_NOT_READY: " + capabilities.reason());
        }
        List<Map<String, Object>> denseResults = profile.mode() == RetrievalProfile.Mode.KEYWORD ? List.of() : results(dense.get());
        if (!wantsLexical) return new LegResult(denseResults, List.of(), false, false, "");
        if (!capabilities.lexicalReady()) return new LegResult(denseResults, List.of(), false, true, "LEXICAL_INDEX_NOT_READY");
        try {
            return new LegResult(denseResults, results(lexical.get()), true, false, "");
        } catch (LexicalSearchUnavailableException unavailable) {
            if (!canDegrade) throw unavailable;
            return new LegResult(denseResults, List.of(), false, true, "LEXICAL_UNAVAILABLE");
        }
    }

    private static AISearchRequest legRequest(AISearchRequest base, int limit, boolean lexical) {
        return AISearchRequest.builder().query(base.getQuery()).entityType(base.getEntityType()).limit(limit)
            .threshold(lexical ? 0.0 : base.getThreshold()).filters(base.getFilters()).metadata(base.getMetadata())
            .sortBy(base.getSortBy()).context(base.getContext()).build();
    }

    private static List<Map<String, Object>> results(AISearchResponse response) {
        if (response == null) throw new IllegalStateException("Retrieval leg returned no execution receipt");
        return response.getResults() == null ? List.of() : response.getResults();
    }

    private static void add(List<ReciprocalRankFusion.RankedList> lists, String source, String type, LegResult result) {
        lists.add(new ReciprocalRankFusion.RankedList(source, type, "vector", attributedResults(result.dense(), type)));
        if (result.lexicalRan()) lists.add(new ReciprocalRankFusion.RankedList(source, type, "lexical", attributedResults(result.lexical(), type)));
    }

    private static List<Map<String, Object>> attributedResults(List<Map<String, Object>> results, String type) {
        if (type == null) return results;
        return results.stream().map(original -> {
            Map<String, Object> metadata = RAGMetadataSupport.normalizeMetadata(original.get("metadata"));
            for (Object declared : new Object[]{original.get("entityType"), original.get("vectorSpace"), metadata.get("vectorSpace")}) {
                if (declared != null && !type.equals(declared.toString())) {
                    throw new SecurityException("Retrieval candidate does not belong to its selected source space");
                }
            }
            Map<String, Object> record = new LinkedHashMap<>(original);
            record.put("entityType", type);
            record.put("vectorSpace", type);
            return record;
        }).toList();
    }

    private record LegResult(List<Map<String, Object>> dense, List<Map<String, Object>> lexical,
                             boolean lexicalRan, boolean degraded, String reason) {
        Map<String, Object> diagnostic(String source, RetrievalCapabilities capabilities) {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("sourceId", source == null ? "" : source);
            values.put("status", "SUCCEEDED");
            values.put("vectorCandidates", dense.size());
            values.put("lexicalCandidates", lexical.size());
            values.put("lexicalSearchUsed", lexicalRan);
            values.put("degraded", degraded);
            values.put("reason", reason);
            values.put("indexGeneration", capabilities.indexGeneration() == null ? "" : capabilities.indexGeneration());
            return Map.copyOf(values);
        }
    }
}
