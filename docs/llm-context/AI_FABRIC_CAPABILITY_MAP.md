# AI Fabric Capability Map

| Capability | Main modules | Key code/config |
| --- | --- | --- |
| Core generation | `ai-fabric-core`, `ai-fabric-provider-spring-ai` | `AICoreService`, `AIProviderManager`, `ai.providers.*` |
| Embeddings | `ai-fabric-core`, provider module | `AIEmbeddingService`, `EmbeddingProvider` |
| Semantic search | `ai-fabric-core`, vector provider | `VectorDatabaseService`, `AISearchRequest` |
| Indexing | `ai-fabric-indexing` | `IndexingCoordinator`, `ai-entity-config.yml` |
| RAG retrieval | `ai-fabric-rag` | `RAGService`, `RAGRequest`, retrieved evidence |
| Advanced RAG | `ai-fabric-rag`, `ai-fabric-web` | `AdvancedRAGService`, `/api/ai/advanced-rag/search` |
| Actions | `ai-fabric-core`, action modules | `@AIAction`, `AIActionRegistry`, `ActionAccessMode` |
| Connector actions | `ai-fabric-actions-connector` | connector action catalogs |
| DB action registry | `ai-fabric-actions-registry` | DB-backed action definitions |
| Confirmations | `ai-fabric-chat-session` | pending action stack, confirmation resolvers |
| Chat memory | `ai-fabric-chat-session` | `chat_sessions`, `chat_turns`, `ChatSessionService` |
| PII | `ai-fabric-pii` | detection/sanitization flows |
| Governance | `ai-fabric-governance` | compliance/governance checks |
| Relationship query | `ai-fabric-relationship-query` | relationship schema, query action |
| Behavior insights | `ai-fabric-behavior` | behavior analysis over raw events |
| Migration/backfill | `ai-fabric-migration` | migration jobs and entity backfill |
| Data sync | `ai-fabric-data-sync` | data ingestion/sync endpoints |
| Web endpoints | `ai-fabric-web` | admin/profile/security/RAG web controllers |
| Curated modes | `ai-fabric-curated-*` | prompt/mode bundles and overlays |

## Capability Evidence

Use `examples/real-apps/REAL_APP_CAPABILITIES.md` and each real app README as code-backed evidence.
If a capability has no real app or test evidence, mark it as planned or experimental.

