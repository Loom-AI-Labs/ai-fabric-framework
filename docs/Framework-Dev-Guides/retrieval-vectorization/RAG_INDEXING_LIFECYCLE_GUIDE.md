# RAG Indexing Lifecycle Guide

This guide defines the release-facing lifecycle for `ai-fabric-rag` and the supporting indexing,
data-sync, migration, embedding, and vector-store modules.

Applications upgrading from 0.3 must also follow
`ANNOTATION_LIFECYCLE_0_4_MIGRATION_GUIDE.md`.

`ai-fabric-rag` is intentionally retrieval-focused. It indexes content, retrieves relevant documents,
and builds context for downstream generation. It does not own PII redaction, action execution, or final
LLM answer generation; those are handled by the orchestration and provider layers.

## Module Boundaries

| Concern | Owner |
|---|---|
| Typed entity contract, descriptors, identity, and projection | `ai-fabric-core` |
| Transaction-aware lifecycle, durable work, ordering, retry/dead letter | `ai-fabric-indexing` |
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

Applications describe typed entities with `@AICapable`, `@AIIdentity`, `@AISearchable`, and
`@AIContext`. Annotation-backed entities need no YAML entry. Optional typed YAML can apply
operational policy; YAML-only push entities must declare their complete projection.

The key release rule is that the source entity remains application-owned and the AI projection is an
allowlist:

- Use stable `entityType` values, for example `product`, `faq`, or `ticket`.
- Mark stable identity explicitly with `@AIIdentity` or a supported JPA identity.
- Mark only fields that are safe and useful for each typed destination.
- Keep tenant, source, and authorization metadata explicit and required where policy depends on it.
- Use `priority` for projection ordering and bounded retention, not similarity weighting.
- When `sanitizePII=true`, projection fails closed if sanitization cannot complete.

Declare source lifecycle operations on a public Spring service boundary:

```java
@Transactional
@AIProcess(operation = AIProcessOperation.UPDATE)
public FaqArticle update(FaqArticle article) {
    return repository.saveAndFlush(article);
}
```

Method names do not determine the operation. Result wrappers, argument-owned targets, and void
deletes require an explicit `AIProcessTargetResolver`. Private or self-invoked annotated methods fail
startup validation instead of silently doing nothing.

### 2. Extract

`AIEntityDescriptorRegistry` resolves one immutable contract and `AIEntityProjectionService` builds a
versioned `AIIndexDocument`:

- `entityType`: the vector-space/entity bucket.
- `entityId`: the source entity identifier.
- `semanticSearchText`: approved text sent to the embedding provider.
- `ragContextText`: approved evidence text returned to RAG.
- `vectorMetadata`: bounded filterable provider metadata.
- `llmContext`: bounded structured context approved for model use.
- `responseMetadata`: values approved for API presentation.
- source operation/version, descriptor hash, schema version, timestamp, and correlation ID.

The durable queue stores this class-free projection, never the complete Java entity, class name,
credentials, raw PII, or unrestricted application metadata.

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
the resulting text chunks into approved AI Fabric index documents. AI Fabric still owns vector-space
validation, queueing, retry/dead-letter handling, embedding generation, and vector writes.

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

### 3. Dispatch And Commit

Every entity lifecycle operation first persists projected work:

1. Resolve and validate the descriptor.
2. Resolve stable identity.
3. Build the approved projection.
4. Insert the queue row in the source transaction when one is active.
5. On rollback, retain neither queue work nor provider mutation.
6. On commit, dispatch according to strategy.

`SYNC` attempts provider work after source commit, or immediately when no source transaction exists.
A provider failure leaves durable retryable work. `ASYNC` and `BATCH` are leased by their workers.
All strategies use the same queue payload and execution path.

Applications outside Spring AOP use `AIEntityIndexingGateway.upsert(...)`,
`delete(...)`, or `submit(...)` for an already trusted projection.

### 4. Embed

`RAGService.indexContent(...)` delegates to `AIEmbeddingService`, which calls the configured
`EmbeddingProvider`.

Release expectations:

- Embeddings are provider-selected by configuration, not hardcoded in RAG.
- Offline tests and demos can use deterministic smoke-style embeddings.
- Production deployments should document embedding dimension and model compatibility with the chosen
  vector store.
- Provider failures should fail the indexing operation visibly; do not store content without a matching
  embedding.

### 5. Upsert

For annotation lifecycle, migration, data sync, and document ingestion,
`IndexingOperationExecutor` performs exactly one embedding call and one vector upsert:

```java
aiEntityIndexingGateway.upsert(
    article,
    AIProcessOperation.UPDATE,
    IndexingStrategy.SYNC
);
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

`RAGService.indexContent(...)` remains a direct trusted RAG API and is entity-idempotent.
Re-indexing the same `entityType` and `entityId`
first looks up the existing vector, updates it by vector id when possible, and only stores a new vector
when no existing record is present. If a provider reports an existing entity without a usable vector
id, RAG removes the entity record before storing the replacement so release paths do not knowingly
create duplicate retrieval rows for one source entity.

### 6. Retrieve

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

### 6.1 Evaluate

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

### 7. Update

Updates must preserve the same `entityType` and `entityId`:

- Project the latest source record after the application update succeeds.
- Insert projected work atomically with the source change.
- Re-embed once and upsert the same stable identity.
- Use source version and per-entity work ordering to supersede stale work.

### 8. Delete

Deletes project identity-only work and remove vectors idempotently:

```java
aiEntityIndexingGateway.delete(
    FaqArticle.class,
    "faq-123",
    IndexingStrategy.SYNC
);
```

The expected result is no document returned for that entity in future retrieval. The vector provider
should return a clear false/not-found outcome rather than silently pretending a delete happened.

### 9. Backfill

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

### 10. Queue Worker Semantics

`ai-fabric-indexing` workers lease projected queue rows, establish per-entity ordering state, execute
one typed work operation, and acknowledge the row as completed, superseded, retryable, or dead letter.

Release expectations:

- Processing failures are recorded through `IndexingQueueService.markFailure(...)`, which handles retry
  scheduling and dead-letter transition.
- Completion acknowledgement failures are not reclassified as processing failures; the entry remains
  recoverable through the stuck-work sweep rather than being marked as a failed indexing attempt.
- Failure acknowledgement failures are logged per entry and do not stop the rest of the leased batch.
- The cleanup scheduler reclaims a bounded batch of locked `PROCESSING` entries only when both the
  visibility timeout has expired and `cleanup.stuck-threshold` has elapsed. Reclaimed work follows the
  normal retry/backoff/dead-letter policy, so repeated worker crashes remain visible instead of being
  reset forever.
- Per-entity state is worker-owned, so concurrent first submissions cannot make source transactions
  compete to create ordering rows.
- State tombstones survive deletes so older updates cannot recreate deleted evidence.
- Optional analysis is separate dependent work and cannot run before its indexing dependency completes.

Expose `management.endpoints.web.exposure.include=aifabricEntities,...` to inspect sanitized descriptor,
process-method, and queue readiness. Monitor bounded-cardinality metrics:

- `aifabric.indexing.accepted`
- `aifabric.indexing.completed`
- `aifabric.indexing.failed`
- `aifabric.indexing.retried`
- `aifabric.indexing.dead_lettered`
- `aifabric.indexing.superseded`
- `aifabric.indexing.projection_failures`
- `aifabric.indexing.duration`

## Verification

Targeted module verification:

```bash
mvn -f ai-infrastructure-module/pom.xml -pl ai-fabric-rag -am test
```

The RAG module test suite should prove:

- indexing and retrieval through an in-memory vector store
- source commit/rollback behavior with a real transaction manager
- exactly one upsert or idempotent delete per lifecycle operation
- wrapper, collection, optional, void-delete, and custom identity resolution
- stale work, delete/recreate ordering, retry, and dead-letter transitions
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
