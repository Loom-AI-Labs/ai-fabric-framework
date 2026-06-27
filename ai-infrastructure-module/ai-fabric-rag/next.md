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

Status: Done.

`RAGProperties` defines module defaults for:

- `defaultLimit`
- `defaultThreshold`
- `enableHybridSearch`
- `enableContextualSearch`
- indexing batch/content settings
- advanced defaults such as expansion level, reranking strategy, context optimization level, max documents, and max results per query
- caching flags and TTLs.

Implemented behavior:

- `RAGAutoConfiguration` now passes bound `RAGProperties` into `RAGService` and
  `AdvancedRAGService`.
- `RAGService` uses configured default limit, threshold, hybrid/contextual defaults, and indexing
  content length.
- `AdvancedRAGService` uses configured expansion level, max documents, max results per query,
  reranking strategy, context optimization level, and hybrid/contextual defaults.
- Request DTO defaults for these fields are nullable, so omitted request options use the service
  configuration instead of hidden DTO constants.

Implemented coverage:

- `RAGServicePropertiesTest` proves configured request defaults, explicit request overrides, search
  mode defaults, explicit hybrid override, and indexing content truncation.
- `AdvancedRAGServiceTest` proves configured advanced defaults propagate into delegated
  `RAGRequest`s and response metadata.
- `RAGAutoConfigurationTest` proves bound properties reach the auto-configured `RAGService`.

### 2. Make `indexContent` Upsert-Safe

Status: Done.

`RAGService.indexContent(...)` now looks up the existing vector by `entityType` and `entityId`,
updates by vector id when possible, and only stores a new record when no existing entity record is
present. If a provider returns an existing record without a vector id, the service removes by entity
before storing the replacement.

Implemented coverage:

- Mocked `RAGServiceIndexContentTest` coverage for insert, update, update-miss replacement, and
  no-vector-id replacement paths.
- Memory-vector integration coverage proving re-indexing the same entity leaves one retrievable
  document with updated content and metadata.

### 3. Clarify And Improve Hybrid Search

Status: Done.

No concrete vector provider currently overrides native hybrid search or keyword search support. Default
hybrid search still falls back to vector search unless a custom provider or `SearchSource` handles
hybrid behavior, but response metadata now makes that explicit.

Implemented behavior:

- `RAGSearchExecutor` keeps the existing fallback retrieval behavior.
- `RAGResponse.hybridSearchUsed` now reports actual/native hybrid use instead of only echoing the
  request flag.
- Response metadata includes `hybridSearchRequested`, `hybridSearchUsed`, `hybridSearchMode`,
  `searchExecutionPath`, `vectorProviderSupportsHybridSearch`, and `contextualSearchUsed`.
- `SearchSource` now has a default `supportsHybridSearch()` capability hook. Search-source RAG only
  reports `hybridSearchUsed=true` when a successful source advertises hybrid support.
- Provider fallback is reported as `hybridSearchMode=fallback_vector`; native provider hybrid is
  reported as `hybridSearchMode=native`.

Implemented coverage:

- `RAGServicePropertiesTest` proves requested hybrid is reported as vector fallback when the vector
  provider does not advertise native hybrid support.
- `RAGServicePropertiesTest` proves native hybrid is reported when `supportsHybridSearch()` is true.
- `RAGServiceSearchSourceRegistryTest` proves source-registry hybrid reporting is driven by successful
  source capability diagnostics.

### 4. Optimize Advanced Semantic Reranking

Status: Done.

Implemented behavior:

- `AdvancedRAGService.rerankBySemanticSimilarity(...)` still embeds the query once, then uses
  `AIEmbeddingService.generateEmbeddings(...)` for retrieved documents that do not already carry
  embeddings.
- Existing `RAGDocument.embeddings` are reused instead of recomputed.
- `RAGProperties.AdvancedProperties.maxSemanticRerankDocuments` caps how many candidates are embedded
  during semantic reranking; overflow documents are preserved after the semantically scored set.
- Batch embedding failures fall back to the original retrieval order instead of failing the whole
  advanced RAG request.

Implemented coverage:

- `AdvancedRAGServiceTest` proves existing document embeddings are reused and only missing document
  embeddings are batched.
- `AdvancedRAGServiceTest` proves the semantic rerank cap bounds batch embedding work while preserving
  overflow documents.
- `AdvancedRAGServiceTest` proves batch embedding failure keeps the request successful and returns the
  original retrieval order.

### 5. Use A Bounded Executor For Advanced Search Fan-Out

Status: Done.

Implemented behavior:

- `RAGProperties.AdvancedProperties` now exposes `maxParallelSearches`, defaulting to `4`.
- `RAGAutoConfiguration` creates a named, daemon, fixed-size `advancedRagSearchExecutor` when
  advanced RAG is enabled.
- `AdvancedRAGService.performMultiStrategySearch(...)` runs expanded-query searches on the injected
  executor instead of the common pool.
- Direct service construction preserves the legacy common-pool fallback unless a caller supplies an
  executor.

Implemented coverage:

- `RAGAutoConfigurationTest` proves `ai.infrastructure.rag.advanced.max-parallel-searches` binds into
  the configured executor size.
- `AdvancedRAGServiceTest` proves expanded-query fan-out is scheduled through the provided executor.

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

1. Wire `RAGProperties` and add tests. Done.
2. Make `indexContent` upsert-safe and add memory-vector integration coverage. Done.
3. Add bounded executor support for advanced search fan-out. Done.
4. Batch semantic reranking embeddings. Done.
5. Improve hybrid-search metadata and docs. Done.
6. Revisit source ranking normalization and duplicate filtering.

## Verification To Run After Changes

```bash
mvn -f ai-infrastructure-module/pom.xml -pl ai-fabric-rag -am test
git diff --check
```

Recommended focused tests to add or update:

- `RAGAutoConfigurationTest` for property binding into services. Done for `RAGService`.
- `RAGServiceMemoryVectorIntegrationTest` for upsert/re-index behavior. Done.
- `AdvancedRAGServiceTest` for bounded fan-out and batch embedding reranking.
- `RAGServiceSearchSourceRegistryTest` for hybrid/fallback metadata and source diagnostics. Done.
