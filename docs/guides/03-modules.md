# 3. Modules Reference

AI Fabric is modular: add only what you need. All modules share the group `io.github.loom-ai-labs`
and have their versions managed by `ai-fabric-bom`. Add a module by its `artifactId`.

## Foundation

| Module | Purpose |
|--------|---------|
| `ai-fabric-bom` | Bill of materials — import it to manage all module versions. |
| `ai-fabric-core` | Core abstractions and services (`AIProvider`, `EmbeddingProvider`, `VectorDatabaseService`, `AICoreService`, orchestration/intent primitives) plus the main auto-configuration. |
| `ai-fabric-starter` | Spring Boot starter that activates the core auto-configuration. Start here. |
| `ai-fabric-web` | Optional web layer (controllers/endpoints) for exposing AI features over HTTP. |

Enable the framework with `@EnableAIInfrastructure` (package `ai.fabric.annotation`).

## LLM / generation providers

Add one or more; select the active one with `ai.providers.llm-provider`.

| Module | Provider |
|--------|----------|
| `ai-fabric-provider-spring-ai` | OpenAI |
| `ai-fabric-provider-spring-ai` | Azure OpenAI |
| `ai-fabric-provider-spring-ai` | Anthropic |
| `ai-fabric-provider-spring-ai` | Cohere |
| `ai-fabric-provider-spring-ai` | Google Gemini |
| `ai-fabric-provider-starter` | Convenience aggregator for provider wiring. |

## Embedding providers

Select with `ai.providers.embedding-provider`. Several LLM provider modules also contribute an
embedding provider (OpenAI, Azure, Cohere, Gemini). For **local, offline** embeddings:

| Module | Purpose |
|--------|---------|
| `ai-fabric-onnx-starter` | Local ONNX-based embeddings — no API calls. Requires a local model file (see [Installation](01-installation.md)). |

## Vector stores

Select with `ai.vector-db.type`.

| Module | `type` | Notes |
|--------|--------|-------|
| `ai-fabric-vector-lucene` | `lucene` | Local, file-based. Good default for development and single-node apps. |
| `ai-fabric-vector-memory` | `memory` | In-process, ephemeral. Great for tests and demos. |
| `ai-fabric-vector-qdrant` | `qdrant` | Qdrant (self-hosted or cloud). |
| `ai-fabric-vector-pinecone` | `pinecone` | Pinecone (cloud). |
| `ai-fabric-vector-weaviate` | `weaviate` | Weaviate. |
| `ai-fabric-vector-milvus` | `milvus` | Milvus. |

## Retrieval, search & natural language

| Module | Purpose |
|--------|---------|
| `ai-fabric-rag` | Retrieval-augmented generation primitives over the vector store. |
| `ai-fabric-relationship-query` | Translate natural-language questions into JPA/JPQL queries over your entities ("natural language → query"). |
| `ai-fabric-indexing` | Index your domain content into the vector store. |
| `ai-fabric-data-sync` | Keep indexed content in sync with your data as it changes. |
| `ai-fabric-migration` | Bulk backfill/migration of existing data into the index. |

For the indexing lifecycle behind RAG (annotate, extract, embed, upsert, update, delete, backfill),
see [RAG Indexing Lifecycle Guide](../Framework-Dev-Guides/retrieval-vectorization/RAG_INDEXING_LIFECYCLE_GUIDE.md).

## Actions & orchestration

| Module | Purpose |
|--------|---------|
| `ai-fabric-actions-registry` | Register actions/tools the model can invoke; manages pending/draft actions. |
| `ai-fabric-actions-registry-liquibase` | Liquibase-backed persistence for the actions registry. |
| `ai-fabric-actions-connector` | Connect actions to external systems. |
| `ai-fabric-retrieval-connector` | Connect retrieval to external content sources. |
| `ai-fabric-relay` | Runtime relay/transport primitives. |

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
