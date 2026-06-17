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
downstream generation.

### 6. Update

Updates should preserve the same `entityType` and `entityId`:

- Re-extract content from the latest source record.
- Re-embed the new text.
- Update the existing vector when supported, or store a replacement and remove the old vector.
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

## Verification

Targeted module verification:

```bash
mvn -f ai-infrastructure-module/pom.xml -pl ai-fabric-rag -am test
```

The RAG module test suite should prove:

- indexing and retrieval through an in-memory vector store
- deterministic offline embedding behavior
- metadata filtering and attribution
- optimized/embedding query selection
- search-source aggregation, skip, and degraded failure behavior
- Spring Boot auto-configuration activation and custom-provider backoff
- null/partial request safety

## Operational Notes

- Keep RAG retrieval deterministic enough to test locally.
- Keep `ai-fabric-retrieval-connector` positioned as extension-point support until its contract coverage
  is broader.
- Do not market demo smoke embeddings as semantic model quality; they prove wiring, not real recall.
- Prefer explicit metadata and support matrices over claims of provider parity.
