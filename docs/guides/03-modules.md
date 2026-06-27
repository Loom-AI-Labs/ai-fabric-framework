# 3. Modules Reference

AI Fabric is modular: add only what you need. All modules share the group `io.github.loom-ai-labs`
and have their versions managed by `ai-fabric-bom`. Add a module by its `artifactId`.

## Foundation

| Module | Purpose |
|--------|---------|
| `ai-fabric-bom` | Bill of materials — import it to manage all module versions. |
| `ai-fabric-core` | Core abstractions and services (`AIProvider`, `EmbeddingProvider`, `VectorDatabaseService`, `AICoreService`, orchestration/intent primitives) plus the main auto-configuration. |
| `ai-fabric-starter` | Spring Boot starter that activates the core auto-configuration. Start here. |
| `ai-fabric-web` | Optional web layer for exposing AI features over HTTP. Controllers auto-register only when their backing service beans are present and can be toggled under `ai.web.controllers.*`. |

Enable the framework with `@EnableAIInfrastructure` (package `ai.fabric.annotation`).

## LLM / generation providers

Add one or more; select the active one with `ai.providers.llm-provider`.

| Module | Provider |
|--------|----------|
| `ai-fabric-provider-spring-ai` | OpenAI |
| `ai-fabric-provider-spring-ai` | Azure OpenAI |
| `ai-fabric-provider-spring-ai` | Anthropic |
| `ai-fabric-provider-spring-ai` | Google Gemini |
| `ai-fabric-provider-starter` | Convenience aggregator for provider wiring. |

The Spring AI provider executes chat through Spring AI `ChatClient`, preserves AI Fabric provider
selection/fallback/governance, supports trusted request-scoped Spring AI advisors, and can attach
guarded AI Fabric actions as provider-native tool callbacks. It also contributes a redacted
`SpringAiObservationDiagnostics` bridge when an application `ObservationRegistry` is present. See the
[Spring AI Provider Integration Guide](../Framework-Dev-Guides/runtime-integration/SPRING_AI_PROVIDER_INTEGRATION_GUIDE.md).

## Embedding providers

Select with `ai.providers.embedding-provider`. `ai-fabric-provider-spring-ai` contributes Spring
AI-backed embedding providers for OpenAI, Azure OpenAI, Google Gemini, and the opt-in
`spring-ai-onnx` local transformer path. Anthropic and Cohere embeddings are not active in this
release because they are not exposed through the Spring AI provider path used by AI Fabric.

For **local, offline** embeddings:

| Module | Purpose |
|--------|---------|
| `ai-fabric-onnx-starter` | Local ONNX-based embeddings — no API calls. Requires a local model file (see [Installation](01-installation.md)). |
| `ai-fabric-provider-spring-ai` | Optional Spring AI Transformers ONNX provider selected with `ai.providers.embedding-provider=spring-ai-onnx`. |

## Vector stores

Select with `ai.vector-db.type`.

| Module | `type` | Notes |
|--------|--------|-------|
| `ai-fabric-vector-lucene` | `lucene` | Local, file-based. Supports exact fetch, scalar metadata filtering, scan, clear, and local counts. |
| `ai-fabric-vector-memory` | `memory` | In-process and ephemeral. Great for tests and demos; production profiles require explicit acknowledgement. |
| `ai-fabric-vector-qdrant` | `qdrant` | Qdrant (self-hosted or cloud). Supports native filtered search/scan, exact fetch, clear, and counts. |
| `ai-fabric-vector-pinecone` | `pinecone` | Pinecone (cloud). Supports portable scalar search metadata filters, client-side list/fetch scan metadata filters, exact fetch, clear, and namespace stats. |
| `ai-fabric-vector-weaviate` | `weaviate` | Weaviate. Supports filtered search/scan, exact fetch, clear, and native aggregate counts with visible paged fallback diagnostics. |
| `ai-fabric-vector-milvus` | `milvus` | Milvus. Supports filtered search/scan with stored metadata JSON expressions, exact fetch, clear, and collection-statistics counts with scan fallback. |

## Retrieval, search & natural language

| Module | Purpose |
|--------|---------|
| `ai-fabric-rag` | Retrieval-augmented generation primitives over the vector store; includes opt-in Spring AI RAG evaluation helpers for tests/release gates. |
| `ai-fabric-relationship-query` | Translate natural-language questions into JPA/JPQL queries over your entities ("natural language → query"). |
| `ai-fabric-indexing` | Index your domain content into the vector store; includes optional Spring AI document reader/chunker helpers when Spring AI commons is on the classpath. |
| `ai-fabric-data-sync` | Keep indexed content in sync with your data as it changes. |
| `ai-fabric-migration` | Bulk backfill/migration of existing data into the index. |

For the indexing lifecycle behind RAG (annotate, extract, embed, upsert, update, delete, backfill),
see [RAG Indexing Lifecycle Guide](../Framework-Dev-Guides/retrieval-vectorization/RAG_INDEXING_LIFECYCLE_GUIDE.md)
and [Migration Backfill Guide](../Framework-Dev-Guides/retrieval-vectorization/MIGRATION_BACKFILL_GUIDE.md).

## Actions & orchestration

| Module | Purpose |
|--------|---------|
| `ai-fabric-actions-registry` | Optional DB-backed connector action registry with register/list/delete endpoints, API key protection, persistence validation, and runtime handler loading into the unified action registry. |
| `ai-fabric-actions-registry-liquibase` | Optional Liquibase helper that supplies safe DB action registry changelog defaults when the host application has not configured its own Liquibase changelog. |
| `ai-fabric-actions-connector` | File/DB connector action catalogs plus validated outbound execution via the Customer Connector API. |
| `ai-fabric-retrieval-connector` | Optional documents-only `RAGProvider` that connects retrieval to external content sources and backs off when a custom `RAGProvider` is present. |
| `ai-fabric-relay` | Runnable customer-side Relay for `/actions/execute` and documents-only `/retrieval/search`, with inbound auth, replay protection, rate limits, idempotency, Redis/in-memory stores, and validated upstream response handling. |

(Core orchestration and intent-extraction primitives live in `ai-fabric-core`.)

## Trust, safety & operations

| Module | Purpose |
|--------|---------|
| `ai-fabric-pii` | Detect and redact PII in customer-facing flows. |
| `ai-fabric-governance` | Governance controls (access policy, etc.). |
| `ai-fabric-chat-session` | Conversation context and turn recording for chat experiences. |
| `ai-fabric-behavior` | Behavioral signals (e.g. churn/sentiment insights). |

## Curated packs

Reusable, opinionated prompt/action packs you can drop in:

| Module | Purpose |
|--------|---------|
| `ai-fabric-curated-default` | General-purpose curated pack. |
| `ai-fabric-curated-commerce` | Commerce-oriented pack. |
| `ai-fabric-curated-support` | Customer-support-oriented pack. |

## Choosing a starting set

- **Semantic search / RAG:** `ai-fabric-starter` + an embedding provider (`ai-fabric-onnx-starter`)
  + a vector store (`ai-fabric-vector-lucene`) + `ai-fabric-rag`.
- **Natural-language queries over your DB:** add `ai-fabric-relationship-query` + an LLM provider.
- **Customer support bot with privacy:** add `ai-fabric-pii` (+ `ai-fabric-chat-session`).
- **Tool/▶action execution:** add `ai-fabric-actions-registry`.

## Next

→ [Configuration Reference](04-configuration.md)
