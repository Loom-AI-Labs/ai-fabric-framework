# Indexing And Retrieval

## Choose The Ingestion Contract

Use the contract that matches source ownership and receipt requirements:

1. **Annotation lifecycle:** use `@AICapable`, `@AIIdentity`, `@AISearchable`, `@AIContext`, and `@AIProcess` when an application-owned service result can drive create/update/delete synchronization.
2. **Explicit indexing gateway:** use `AIEntityIndexingGateway` when the application must return and reconcile a durable work ID through `IndexingWorkStatus`.
3. **Push data sync:** use the released data-sync API when an authorized producer submits already-projected records to an independently deployed consumer.
4. **Document indexing:** use the indexing document contract for files, versions, extraction, chunking, replacement, and work reconciliation. The application owns the source object and authorization; the vector store remains derived and rebuildable.
5. **Retrieval connector:** implement the connector boundary when documents must stay in an external approved search system rather than being copied into an AI Fabric vector provider.

Read the authoritative guides before implementing:

- [Data Sync Push API](../../../docs/Framework-Dev-Guides/retrieval-vectorization/DATA_SYNC_PUSH_API_GUIDE.md)
- [RAG Indexing Lifecycle](../../../docs/Framework-Dev-Guides/retrieval-vectorization/RAG_INDEXING_LIFECYCLE_GUIDE.md)
- [Document Indexing Migration](../../../docs/Framework-Dev-Guides/retrieval-vectorization/DOCUMENT_INDEXING_0_8_MIGRATION_GUIDE.md)
- [Embedding Query Composition](../../../docs/Framework-Dev-Guides/retrieval-vectorization/RAG_EMBEDDING_QUERY_COMPOSITION_GUIDE.md)

## Preserve Source Authority

- Keep the relational/object/document source in application-owned storage.
- Use stable entity/document IDs and a monotonic source version.
- Upsert a new version and reconcile durable indexing work before retiring the prior active version.
- Return typed work state; do not infer completion from a successful enqueue response.
- Delete or supersede derived vectors when source data is removed or replaced.
- Store only approved searchable text and bounded metadata. Keep credentials, unrestricted source objects, and private fields out of vector payloads.

## Retrieval Boundary

- Define vector spaces by stable evidence domain, not by phrases in the user's query.
- Build the allowed retrieval set from trusted server configuration and identity scope.
- Use model/provider routing only inside that allowlist. A request hint may narrow permitted retrieval but cannot grant a source.
- For compound questions, retrieve across all model-selected allowed domains and preserve source identity through generation.
- Use vector/provider similarity and configured rerankers for ranking. Do not implement orchestration ranking with query-term matching.
- Enforce tenant, deployment, owner, and resource filters at the provider boundary. Fail closed if required filtering cannot be represented.
- Preserve provider scores/order and bounded excerpts. Do not drop valid hits during result normalization.

## Verification

Prove create, update, delete, restart/recovery, stale-version supersession, empty-result behavior, provider failure visibility, and cross-tenant denial. For generation, assert that returned citations identify the indexed source and that answers do not claim evidence absent from the retrieved documents.

Use [`ai-fabric-live-data-sync`](../../../examples/real-apps/ai-fabric-live-data-sync) for entity lifecycle patterns and [`document-ingestion-workbench`](../../../examples/real-apps/document-ingestion-workbench) for file/document lifecycle patterns.
