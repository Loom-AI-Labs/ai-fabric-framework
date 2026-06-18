# RAG Module Next Cleanup Notes

These notes capture the follow-up audit after the RAG release cleanup.

## Status

- No placeholder classes or unfinished RAG service implementations were found.
- The module is release-usable after the cleanup commit, but a few optimization and production-hardening items should be handled next.
- The branch was clean for RAG work after commit, with only the pre-existing `oldsession.txt` left untracked.

## Stub And Empty Implementation Check

Intentional extension-point defaults exist, but they are not incomplete module implementations:

- `SearchSourceRegistry.recordSearchExecution(...)` defaults to leaving runtime health state unchanged for registries that do not track it.
- `SearchSourceRegistry.adminDiagnostics()` returns an empty map by default.
- `VectorDatabaseService.keywordSearch(...)` remains an optional provider capability; consumers should check `supportsKeywordSearch()` before using it.
- `VectorDatabaseService.hybridSearch(...)` falls back to vector search by default.
- `supportsHybridSearch()` and `supportsKeywordSearch()` return `false` by default.

These are acceptable SPI defaults, but the public docs and examples should avoid implying that all providers perform native hybrid or keyword retrieval today.

## Main Findings

### 1. Wire `RAGProperties` Into The Services

`RAGProperties` defines module defaults for:

- `defaultLimit`
- `defaultThreshold`
- `enableHybridSearch`
- `enableContextualSearch`
- indexing batch/content settings
- advanced defaults such as expansion level, reranking strategy, context optimization level, max documents, and max results per query
- caching flags and TTLs

Current behavior still uses hardcoded defaults in `RAGService` and `AdvancedRAGService`.

Recommended change:

- Inject `RAGProperties` through `RAGAutoConfiguration`.
- Use it in `RAGService` for default limit, threshold, hybrid/contextual defaults, and indexing content length.
- Use it in `AdvancedRAGService` for expansion level, max documents, max results, reranking strategy, and context optimization level.
- Add auto-configuration and service tests proving property values override hardcoded defaults.

### 2. Make `indexContent` Upsert-Safe

`RAGService.indexContent(...)` currently stores a vector directly. Re-indexing the same entity can create duplicate vectors depending on provider behavior.

Recommended change:

- Check `vectorDatabaseService.getVectorByEntity(entityType, entityId)` or `vectorExists(...)` before storing.
- If a record exists, call `updateVector(...)` with the existing vector id.
- If it does not exist, call `storeVector(...)`.
- Preserve current metadata and embedding behavior.
- Add memory-vector integration coverage for re-indexing the same entity and asserting one retrievable document with updated content.

### 3. Clarify And Improve Hybrid Search

No concrete vector provider currently overrides native hybrid search or keyword search support. Today, default hybrid search falls back to vector search unless a custom provider or `SearchSource` handles hybrid behavior.

Recommended change:

- Keep the fallback behavior, but expose in metadata whether hybrid was native or fallback.
- Consider using `supportsHybridSearch()` to set `hybridSearchUsed` more accurately.
- Add provider-specific native hybrid implementations where feasible.
- Update docs to say hybrid is provider-dependent.

### 4. Optimize Advanced Semantic Reranking

`AdvancedRAGService.rerankBySemanticSimilarity(...)` embeds each document one by one.

Recommended change:

- Use `AIEmbeddingService.generateEmbeddings(...)` for batch document embeddings.
- Reuse existing document similarity scores when document embeddings are not required.
- Cap rerank input size before embedding to avoid expensive expanded-query fan-out.
- Add tests proving semantic reranking uses batch embeddings and preserves fallback behavior when embedding fails.

### 5. Use A Bounded Executor For Advanced Search Fan-Out

`AdvancedRAGService.performMultiStrategySearch(...)` uses `CompletableFuture.supplyAsync(...)` without an explicit executor, which uses the common pool.

Recommended change:

- Inject a bounded executor or Spring `TaskExecutor`.
- Add a max parallelism property under `ai.infrastructure.rag.advanced`.
- Preserve current behavior when no executor is configured.
- Add tests around query fan-out count and graceful failure handling.

### 6. Avoid Double Filtering Where Possible

`RAGService.performRag(...)` now passes request filters into the backend metadata-filter channel and also filters mapped documents in memory.

This is safe and useful as a defensive fallback, but it can duplicate work when the provider supports metadata filtering.

Recommended change:

- Keep the in-memory safety filter for now.
- Later, only apply post-filtering when provider filtering is unsupported or when the search source cannot guarantee filter enforcement.

### 7. Improve Merged Search Source Ranking

`RAGSearchExecutor` merges all source results, sorts by score, then deduplicates and limits.

Recommended change:

- Keep current behavior for release.
- Consider source-aware tie breaking or score normalization across heterogeneous sources.
- Add optional per-source result caps to prevent one source from dominating merged results.

## Suggested Implementation Order

1. Wire `RAGProperties` and add tests.
2. Make `indexContent` upsert-safe and add memory-vector integration coverage.
3. Add bounded executor support for advanced search fan-out.
4. Batch semantic reranking embeddings.
5. Improve hybrid-search metadata and docs.
6. Revisit source ranking normalization and duplicate filtering.

## Verification To Run After Changes

```bash
mvn -f ai-infrastructure-module/pom.xml -pl ai-fabric-rag -am test
git diff --check
```

Recommended focused tests to add or update:

- `RAGAutoConfigurationTest` for property binding into services.
- `RAGServiceMemoryVectorIntegrationTest` for upsert/re-index behavior.
- `AdvancedRAGServiceTest` for bounded fan-out and batch embedding reranking.
- `RAGServiceSearchSourceRegistryTest` for hybrid/fallback metadata and source diagnostics.
