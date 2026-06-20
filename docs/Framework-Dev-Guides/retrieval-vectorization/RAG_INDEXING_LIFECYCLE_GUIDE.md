# RAG Indexing Lifecycle Guide

This guide defines the release-facing lifecycle for `ai-fabric-rag` and the supporting indexing,
data-sync, migration, embedding, and vector-store modules.

`ai-fabric-rag` is intentionally retrieval-focused. It indexes content, retrieves relevant documents,
and builds context for downstream generation. It does not own PII redaction, action execution, or final
LLM answer generation; those are handled by the orchestration and provider layers.

## Module Boundaries

| Concern | Owner |
|---|---|
| Entity annotations and searchable field declarations | `ai-fabric-core` |
| Content extraction from entities/events | `ai-fabric-indexing`, application code |
| Embedding generation | active `EmbeddingProvider` via `AIEmbeddingService` |
| Vector persistence/search | active `VectorDatabaseService` |
| Retrieval response/context assembly | `ai-fabric-rag` |
| Bulk backfill | `ai-fabric-migration` |
| Ongoing change propagation | `ai-fabric-data-sync` |
| Final user-facing generation | core orchestration/provider layer |

## Configuration Defaults

`ai.infrastructure.rag` owns RAG runtime defaults. Request fields such as limit, threshold,
hybrid/contextual mode, and advanced expansion/reranking options are overrides; when they are omitted,
the service applies the configured module defaults.

```yaml
ai:
  infrastructure:
    rag:
      default-limit: 10
      default-threshold: 0.7
      enable-hybrid-search: false
      enable-contextual-search: false
      indexing:
        max-content-length: 10000
      advanced:
        default-expansion-level: 3
        default-reranking-strategy: semantic
        default-context-optimization-level: medium
        max-documents: 10
        max-results-per-query: 20
        max-parallel-searches: 4
        max-semantic-rerank-documents: 100
```

Use request-level values only for per-call overrides. This keeps deployment defaults centralized and
prevents DTO construction defaults from silently changing production retrieval behavior.

## Lifecycle

### 1. Annotate

Applications describe searchable entities with framework annotations and/or `ai-entity-config.yml`.
The key release rule is that the entity model remains the source of truth:

- Use stable `entityType` values, for example `product`, `faq`, or `ticket`.
- Mark only fields that are safe and useful for retrieval.
- Keep tenant, source, and authorization metadata explicit.
- Do not rely on the RAG module to sanitize sensitive fields; sanitize before indexing.

### 2. Extract

The indexing layer builds an indexable payload from application data:

- `entityType`: the vector-space/entity bucket.
- `entityId`: the source entity identifier.
- `content`: the text sent to the embedding provider and later used in context.
- `metadata`: source, tenant, category, timestamps, attribution label, and filterable fields.

Recommended metadata keys:

| Key | Purpose |
|---|---|
| `knowledgeSourceId` | Stable source identifier. |
| `knowledgeSourceType` | Source category, such as `database`, `connector`, or `deployment-private-vector`. |
| `knowledgeSourceAdapterType` | Adapter/vector provider type for diagnostics. |
| `knowledgeSourceAttributionLabel` | User-safe label shown as document source. |
| `tenantId` or equivalent | Tenant isolation and filtering. |
| `category` | Lightweight result filtering/faceting. |

#### Spring AI Document Ingestion

`ai-fabric-indexing` includes an optional Spring AI document bridge for trusted ingestion jobs. It
uses Spring AI `DocumentReader` and `DocumentTransformer` APIs for parsing and chunking, then turns
the resulting text chunks into normal AI Fabric `IndexingRequest` rows. AI Fabric still owns vector
space validation, queueing, retry/dead-letter handling, embedding generation, and vector writes.

```java
SpringAiTrustedResourcePolicy policy = SpringAiTrustedResourcePolicy.trustedRoot(Path.of("/srv/kb"));
DocumentReader reader = readerFactory.textReader(new FileSystemResource("/srv/kb/policy.txt"), policy);

adapter.enqueue(reader, SpringAiDocumentIndexingOptions.builder()
    .entityType("faq")
    .sourceId("policy-handbook")
    .sourceName("Policy Handbook")
    .build());
```

Release rules for this bridge:

- `entityType` must already exist in AI Fabric configuration and be indexable.
- Remote URL resources are rejected; file resources must sit under configured trusted roots.
- Spring AI document metadata is bounded and sanitized before queueing. URL/path/secret-like keys and
  unsupported nested values are dropped instead of persisted.
- Chunk IDs are deterministic from source id, document id, and chunk index, so repeated ingestion
  updates the same logical chunks.

### 3. Embed

`RAGService.indexContent(...)` delegates to `AIEmbeddingService`, which calls the configured
`EmbeddingProvider`.

Release expectations:

- Embeddings are provider-selected by configuration, not hardcoded in RAG.
- Offline tests and demos can use deterministic smoke-style embeddings.
- Production deployments should document embedding dimension and model compatibility with the chosen
  vector store.
- Provider failures should fail the indexing operation visibly; do not store content without a matching
  embedding.

### 4. Upsert

After embedding, the vector store persists the content and metadata:

```java
ragProvider.indexContent("faq", "faq-123", content, metadata);
```

The vector provider must keep these fields together:

- vector id
- entity type
- entity id
- content
- embedding
- metadata

The memory vector store is suitable for tests and demos. Lucene is suitable for local file-backed
development. Qdrant, Pinecone, Weaviate, and Milvus are production-path stores depending on deployment
requirements.

`RAGService.indexContent(...)` is entity-idempotent. Re-indexing the same `entityType` and `entityId`
first looks up the existing vector, updates it by vector id when possible, and only stores a new vector
when no existing record is present. If a provider reports an existing entity without a usable vector
id, RAG removes the entity record before storing the replacement so release paths do not knowingly
create duplicate retrieval rows for one source entity.

### 5. Retrieve

`performRag(...)` runs the retrieval-focused path:

1. Resolve the embedding query from request metadata (`embeddingQuery`, then `optimizedQuery`, then
   `query`).
2. Generate the query embedding.
3. Run vector search through the configured search stack.
4. Normalize result metadata and attribution.
5. Apply request filters.
6. Return documents and a context string.

`performRAGQuery(...)` keeps compatibility with hybrid/contextual search flags and returns context for
downstream generation. Hybrid search is provider-dependent:

- `hybridSearchRequested` records whether the request asked for hybrid retrieval.
- `hybridSearchUsed` records whether AI Fabric can prove a native/provider or search-source hybrid
  path was used.
- `hybridSearchMode` is `native`, `search_source`, `fallback_vector`, `not_reported_by_sources`, or
  `not_requested`.
- `searchExecutionPath` identifies the path, such as `vector_database_hybrid`,
  `search_source_registry`, `vector_database_contextual`, or `default_semantic`.
- `vectorProviderSupportsHybridSearch` mirrors the active vector provider capability for direct vector
  provider execution.

When a provider does not advertise `supportsHybridSearch()`, AI Fabric preserves safe retrieval by
falling back to vector search and reports `hybridSearchMode=fallback_vector`.

### 5.1 Evaluate

RAG quality checks are opt-in test/release gates, not part of the default retrieval hot path. When
`spring-ai-client-chat` and a Spring AI `ChatClient.Builder` are available, enable the helper with:

```yaml
ai:
  infrastructure:
    rag:
      evaluation:
        enabled: true
```

`SpringAiRagEvaluationService` maps retrieved `RAGResponse` documents into Spring AI
`EvaluationRequest` instances and can run Spring AI relevancy and fact-checking evaluators. The
service bounds document content, drops embedding/url/path/secret-like metadata, and returns an AI
Fabric result shape with evaluator name, document count, pass/fail, score, and feedback.

Use it in tests to check:

- whether retrieved documents are relevant to the user query
- whether a generated answer is supported by the retrieved context
- whether regressions lowered evaluator score below an application-defined release threshold

### 6. Update

Updates should preserve the same `entityType` and `entityId`:

- Re-extract content from the latest source record.
- Re-embed the new text.
- Update the existing vector when supported, or remove the old entity record before storing a
  replacement.
- Refresh `_indexedUpdatedAt` or equivalent metadata through the indexing/data-sync layer.

### 7. Delete

Deletes should remove vectors by entity identity:

```java
ragProvider.removeContent("faq", "faq-123");
```

The expected result is no document returned for that entity in future retrieval. The vector provider
should return a clear false/not-found outcome rather than silently pretending a delete happened.

### 8. Backfill

Backfill belongs to `ai-fabric-migration` or application-owned migration jobs:

1. Page through source records.
2. Extract indexable content and metadata.
3. Embed in bounded batches.
4. Upsert vectors.
5. Record progress and failed records.
6. Resume safely after interruption.

For release validation, use the memory vector store and deterministic embeddings first, then repeat with
the production vector provider.

For detailed job lifecycle, pause/resume/cancel, filtering, and progress semantics, see
`MIGRATION_BACKFILL_GUIDE.md`.

### 9. Queue Worker Semantics

`ai-fabric-indexing` workers lease queue rows, execute the requested action plan, and then acknowledge
the row as completed or failed.

Release expectations:

- Processing failures are recorded through `IndexingQueueService.markFailure(...)`, which handles retry
  scheduling and dead-letter transition.
- Completion acknowledgement failures are not reclassified as processing failures; the entry remains
  recoverable through visibility-timeout reset rather than being marked as a failed indexing attempt.
- Failure acknowledgement failures are logged per entry and do not stop the rest of the leased batch.
- The cleanup scheduler resets expired `PROCESSING` entries so transient database or worker crashes do not
  strand work permanently.

## Verification

Targeted module verification:

```bash
mvn -f ai-infrastructure-module/pom.xml -pl ai-fabric-rag -am test
```

The RAG module test suite should prove:

- indexing and retrieval through an in-memory vector store
- re-indexing the same entity updates one vector instead of appending duplicates
- Spring AI document ingestion converts trusted text/JSON resources into bounded indexing requests
  and rejects untrusted URL/outside-root resources
- deterministic offline embedding behavior
- metadata filtering and attribution
- optimized/embedding query selection
- search-source aggregation, skip, and degraded failure behavior
- Spring Boot auto-configuration activation and custom-provider backoff
- opt-in Spring AI RAG evaluation helpers map documents safely and stay disabled by default
- null/partial request safety

## Operational Notes

- Keep RAG retrieval deterministic enough to test locally.
- Keep `ai-fabric-retrieval-connector` positioned as extension-point support until its contract coverage
  is broader.
- Do not market demo smoke embeddings as semantic model quality; they prove wiring, not real recall.
- Prefer explicit metadata and support matrices over claims of provider parity.
