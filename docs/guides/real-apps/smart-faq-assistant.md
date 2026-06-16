# Smart FAQ Assistant

> One-line: offline semantic search over FAQ articles, with an optional RAG-style answer endpoint.

## What it builds
A help-center backend: you store FAQ articles (title, content, category, tags), and instead of keyword matching, users search by meaning. The app indexes each article into a local Lucene vector store using deterministic embeddings, so it runs with zero external keys. The key endpoints live under `@RequestMapping("/api/faq")` — `GET /articles`, `GET /articles/{id}`, `POST /articles`, `GET /search?q=...&limit=...&threshold=...`, `POST /ask` — plus a `@RequestMapping("/api/demo")` controller exposing `POST /seed` and `POST /indexing/reindex/articles`.

## AI Fabric capability showcased
This is the reference example for **semantic search wired through `AICoreService` with a custom in-process `EmbeddingProvider`** — the simplest end-to-end "add vector search to a CRUD app" path, fully offline.

## AI Fabric modules used
- `io.github.loom-ai-labs:ai-fabric-starter:0.2.1` — auto-config, `AICoreService`, search/embedding DTOs.
- `io.github.loom-ai-labs:ai-fabric-vector-lucene:0.2.1` — embedded Lucene vector store (no server).

## Configuration
```yaml
ai:
  enabled: true
  config:
    default-file: ai-entity-config.yml   # maps entity fields → searchable/context
  providers:
    embedding-provider: simple           # uses the app's SimpleHashEmbeddingProvider
  vector-db:
    type: lucene                         # embedded vector index, no external service
  pii-detection:
    enabled: false
  service:
    features:
      enable-generation: ${AI_FAQ_ENABLE_GENERATION:false}  # LLM text generation off by default
      enable-rag: ${AI_FAQ_ENABLE_RAG:false}                # RAG answer composition off by default
```
There is no `application-smoke.yml`: the app already boots offline because `simple`/`lucene` need no keys. The `smoke` profile additionally pulls in the shared `smoke-support` stub beans (deterministic embeddings, no-op LLM) for the framework's own providers.

## How it's wired in Java
- `@EnableAIInfrastructure` on `SmartFaqAssistantApplication` turns on AI Fabric auto-configuration and component scanning.
- `SimpleHashEmbeddingProvider implements ai.fabric.embedding.EmbeddingProvider` — a deterministic, hash-based embedder selected by `embedding-provider: simple`, consuming `AIEmbeddingRequest` / returning `AIEmbeddingResponse`. This is how you supply embeddings without an external model.
- `FaqArticleService` injects `AICoreService` (via `ObjectProvider`) and `AICapabilityService`, then builds an `AISearchRequest` and reads `AISearchResponse`.

```java
// src/main/java/com/ai/fabric/realapps/faq/service/FaqArticleService.java
public List<FaqArticle> semanticSearch(String query, int limit, double threshold) {
    AICoreService aiCoreService = aiCoreServiceProvider.getIfAvailable();
    if (aiCoreService == null) {
        throw new IllegalStateException("AICoreService not available (ensure AI Fabric dependencies are present)");
    }

    AISearchResponse response = aiCoreService.performSearch(AISearchRequest.builder()
        .query(query)
        .entityType(ENTITY_TYPE)
        .limit(limit)
        .threshold(threshold)
        .build());
    // ... map response.getResults() back to FaqArticle ...
}
```

The `simple` provider is resolved lazily through an `ObjectProvider<AICoreService>`: if the AI Fabric beans are absent the service fails fast with a clear message rather than NPE-ing deep in a request. The `AICapabilityService` is the higher-level companion used to drive embedding-on-write so that newly created articles are indexed without manual vector calls.

## Request flow
1. `GET /api/faq/search?q=...` hits `FaqController.search`.
2. `FaqArticleService.semanticSearch` resolves `AICoreService` and submits an `AISearchRequest` (entity type `faq-article`).
3. `AICoreService` embeds the query via the `simple` provider, queries the Lucene vector index, and returns scored hits in `AISearchResponse`.
4. The service maps hits back to `FaqArticle` rows and returns JSON.
5. `POST /ask` runs the same retrieval, then — only when `enable-rag`/`enable-generation` are on — composes a natural-language answer from the top hits; otherwise it returns the retrieved articles.

## Run it
Offline (no keys):
`mvn -pl smart-faq-assistant -f examples/real-apps/pom.xml spring-boot:run -Dspring-boot.run.profiles=smoke`

Seed and search:
```bash
curl -s -X POST http://localhost:8094/api/demo/seed
curl -s -X POST http://localhost:8094/api/demo/indexing/reindex/articles
curl -s "http://localhost:8094/api/faq/search?q=how%20do%20I%20stop%20paying&limit=3"
```

For real: nothing external is required — `simple`/`lucene` already run locally. To enable generated answers, drop the smoke profile and set `AI_FAQ_ENABLE_GENERATION=true` / `AI_FAQ_ENABLE_RAG=true`, then configure a real LLM provider in the `ai.providers` block.

The default port is `8094` (override with `PORT`). The `POST /api/demo/indexing/reindex/articles` call returns `{"indexed": N}` so you can confirm the vector index was rebuilt before searching.

## Take it to your own app
- Add `@EnableAIInfrastructure` to your `@SpringBootApplication` and `ai-fabric-starter` + a vector module — that's the whole bootstrap.
- Implement `EmbeddingProvider` and select it via `ai.providers.embedding-provider` when you want a custom or offline embedder.
- Drive search with `AISearchRequest.builder().query(...).entityType(...).limit(...).threshold(...)` and read `AISearchResponse.getResults()`.
- Keep an explicit reindex endpoint (`reindexAll()`) so you can rebuild the vector index after bulk data changes.
- Gate LLM-heavy features (`enable-generation`, `enable-rag`) behind config flags so the app degrades to pure retrieval when no model is configured.
