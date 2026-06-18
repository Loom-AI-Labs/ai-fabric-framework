# 2. Understanding AI Fabric

This guide builds the mental model you need to use the framework effectively. AI Fabric is a set of
**Spring Boot auto-configured abstractions**: you declare which implementations you want (via
dependencies and configuration), and the framework wires the rest.

## The core idea: program against interfaces, choose implementations by config

AI Fabric separates *what* you do (generate text, embed content, search vectors) from *which*
backend does it. You depend on stable interfaces; you select concrete providers with a property.

```
            your application code
                     │
        ┌────────────┴───────────────┐
        ▼                            ▼
  AIProvider (LLM)          EmbeddingProvider
  openai / anthropic /      onnx / openai /
  cohere / gemini / azure   cohere / gemini / azure
        │                            │
        └──────────┬─────────────────┘
                   ▼
         VectorDatabaseService
   lucene / memory / qdrant / pinecone / weaviate / milvus
```

Switching from OpenAI to Anthropic, or from Lucene to Qdrant, is a dependency + configuration
change — not a code change.

## The key abstractions

| Abstraction | Package | Role |
|-------------|---------|------|
| `AIProvider` | `ai.fabric.provider` | An LLM/generation backend. Has `generateContent(...)`, `generateEmbedding(...)`, `isAvailable()`, `getProviderName()`. |
| `AIProviderManager` | `ai.fabric.provider` | Holds all `AIProvider` beans and resolves the active one from `ai.providers.llm-provider`. |
| `EmbeddingProvider` | `ai.fabric.embedding` | Turns text into vectors. Selected by `ai.providers.embedding-provider`. |
| `AIEmbeddingService` | `ai.fabric.core` | Higher-level embedding service used by the framework (caching, batching). |
| `VectorDatabaseService` | `ai.fabric.rag` | Store/search vectors: `storeVector`, `search`, `hybridSearch`, `keywordSearch`. Selected by `ai.vector-db.type`. |
| `AICoreService` | `ai.fabric.core` | The façade that ties provider + embeddings together for generation flows. |

Concrete providers each carry a **name** (`getProviderName()`), and you select one by that name. For
example `ai.providers.llm-provider: openai` activates the provider whose name is `openai`. This is
also the extension point: register your own `AIProvider` / `EmbeddingProvider` / `VectorDatabaseService`
bean and select it by name.

## How auto-configuration wires it

1. You add `@EnableAIInfrastructure` and the `ai-fabric-starter`.
2. Core auto-configuration registers the shared services and scans the framework's feature packages.
3. Each **provider module** you add (e.g. `ai-fabric-provider-spring-ai`) contributes its provider bean,
   gated by `@ConditionalOnProperty` on `ai.providers.<name>.enabled`.
4. Each **vector module** (e.g. `ai-fabric-vector-lucene`) contributes a `VectorDatabaseService`,
   gated by `ai.vector-db.type`.
5. **Feature modules** (RAG, relationship-query, PII, indexing, …) add their own auto-configurations
   that activate when present on the classpath and enabled in config.

Because selection is by configuration and most beans are `@ConditionalOnMissingBean`, you can
override any piece by supplying your own bean — the framework backs off.

## A typical request flow (RAG / semantic search)

```
user query
   │
   ▼
EmbeddingProvider.generateEmbedding(query)   ── vectorize the query
   │
   ▼
VectorDatabaseService.search(queryVector)    ── retrieve relevant content
   │
   ▼
AIProvider.generateContent(prompt+context)   ── generate a grounded answer
   │
   ▼
response
```

Indexing is the mirror image: content is embedded and stored via `VectorDatabaseService` ahead of
time (often through the **indexing** or **migration** modules), so queries can retrieve it later.

## Higher-level capabilities

On top of the core abstractions, feature modules add:

- **Orchestration & intent** — pipelines that extract intent from natural language and route to the
  right action/answer.
- **Relationship Query** — translate natural language into JPA/JPQL queries over your entities.
- **Actions** — register and execute actions/tools the model can invoke, with a pending/draft store.
- **RAG** — retrieval-augmented generation primitives over the vector store.
- **PII** — detect and redact sensitive data in customer-facing flows.
- **Behavior, chat-session, governance, data-sync, relay, web** — runtime concerns layered on top.

See the [Modules Reference](03-modules.md) for the full list.

## Two naming spaces (don't confuse them)

- **Java packages / Maven artifacts:** `ai.fabric.*` / `ai-fabric-*`.
- **Spring configuration properties:** `ai.providers.*`, `ai.vector-db.*`, `ai.service.features.*`,
  and some `ai.infrastructure.*`. These are property keys, *not* Java packages, and they are stable
  across the rename.

## Next

→ [Modules Reference](03-modules.md)
