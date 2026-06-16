# Cloud Qdrant + OpenAI Vector Search

> One-line: production-shaped semantic search over a Postgres-backed knowledge base, with OpenAI embeddings and a Qdrant vector store.

## What it builds
A knowledge-base service whose articles (title, content, category, tags) live in Postgres and are indexed into Qdrant using OpenAI embeddings. It is the "same domain, cloud backends" counterpart to the Lucene/offline examples — the story is that you swap local → cloud purely through configuration. Key endpoints: `@RequestMapping("/api/search")` with `GET /` (`?q=&limit=&threshold=`); `@RequestMapping("/api/articles")` with `GET /`, `GET /{id}`, `POST /`, `PUT /{id}`, `POST /reindex`; and `@RequestMapping("/api/demo")` with `POST /seed`.

## AI Fabric capability showcased
This is the reference example for **annotation-driven indexing** — `@AICapable` / `@AISearchable` / `@AIContext` on the JPA entity declare what gets embedded and searched — combined with **pluggable cloud backends** (OpenAI embedding provider + Qdrant vector DB) selected entirely by config.

## AI Fabric modules used
- `io.github.loom-ai-labs:ai-fabric-starter:0.2.1` — auto-config, `AICoreService`, search DTOs.
- `io.github.loom-ai-labs:ai-fabric-provider-openai:0.2.1` — OpenAI embedding/LLM provider.
- `io.github.loom-ai-labs:ai-fabric-vector-qdrant:0.2.1` — Qdrant gRPC vector store.

## Configuration
```yaml
ai:
  enabled: true
  config:
    default-file: ai-entity-config.yml
  service:
    features:
      enable-embeddings: true            # generate embeddings on write
      enable-search: true                # expose semantic search
      enable-generation: false           # no LLM generation here
  providers:
    embedding-provider: openai
    llm-provider: openai
    openai:
      api-key: ${AI_PROVIDERS_OPENAI_API_KEY:}
      embedding-model: ${AI_PROVIDERS_OPENAI_EMBEDDING_MODEL:text-embedding-3-small}
    qdrant:
      enabled: true
      host: ${AI_PROVIDERS_QDRANT_HOST:localhost}
      grpc-port: ${AI_PROVIDERS_QDRANT_GRPC_PORT:6334}
      api-key: ${AI_PROVIDERS_QDRANT_API_KEY:}
  vector-db:
    type: qdrant
```
The datasource defaults to Postgres (`jdbc:postgresql://localhost:5435/cloud_vector_search`). The offline `smoke` profile (`application-smoke.yml`) overrides this to H2 in-memory and flips providers to deterministic stubs:
```yaml
ai:
  providers:
    llm-provider: smoke
    embedding-provider: smoke
  vector-db:
    type: memory
```

## How it's wired in Java
- `@EnableAIInfrastructure` on `CloudQdrantOpenaiVectorSearchApplication` enables AI Fabric.
- `KnowledgeBaseArticle` is the domain entity that drives indexing — its annotations tell the framework what to embed:
  - `@AICapable(entityType = "kb-article")` on the class registers it for AI indexing/search.
  - `@AISearchable(weight = 2.0)` on `title`, `@AISearchable(weight = 1.8)` on `content` — these fields are embedded; weights bias relevance.
  - `@AIContext(...)` on `category` and `tags` — carried as metadata/context, not embedded as primary text.
- `KnowledgeBaseArticleService` injects `AICoreService` + `AICapabilityService` and issues `AISearchRequest` / reads `AISearchResponse`.

```java
// src/main/java/com/ai/fabric/realapps/cloudvector/service/KnowledgeBaseArticleService.java
public List<SearchHit> semanticSearch(String query, int limit, double threshold) {
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

    if (response == null || response.getResults() == null || response.getResults().isEmpty()) {
        return List.of();
    }
    return response.getResults().stream()
        .map(row -> toHit(row, response.getMaxScore()))
        .filter(Objects::nonNull)
        .toList();
}
```

## Request flow
1. `POST /api/articles` persists a `KnowledgeBaseArticle`; because it's `@AICapable`, AI Fabric embeds its `@AISearchable` fields via the OpenAI provider and upserts the vector into Qdrant.
2. `GET /api/search?q=...` hits `SearchController`, which calls `KnowledgeBaseArticleService.semanticSearch`.
3. The service submits an `AISearchRequest`; `AICoreService` embeds the query (OpenAI) and runs a nearest-neighbor query against Qdrant.
4. `AISearchResponse` results are mapped to `SearchHit` (with normalized scores) and returned as JSON.

## Run it
Offline (no keys):
`mvn -pl cloud-qdrant-openai-vector-search -f examples/real-apps/pom.xml spring-boot:run -Dspring-boot.run.profiles=smoke`

Seed and search (smoke uses H2 + memory vector store):
```bash
curl -s -X POST http://localhost:8098/api/demo/seed
curl -s "http://localhost:8098/api/search?q=reset%20my%20password&limit=5"
```

For real: drop the smoke profile and supply a Postgres instance (`DB_URL`/`DB_USER`/`DB_PASSWORD`), a running Qdrant (`AI_PROVIDERS_QDRANT_HOST`, `AI_PROVIDERS_QDRANT_GRPC_PORT`, optional `AI_PROVIDERS_QDRANT_API_KEY`), and an OpenAI key (`AI_PROVIDERS_OPENAI_API_KEY`).

## Take it to your own app
- Annotate your JPA entity with `@AICapable` and mark embeddable fields with `@AISearchable` (use weights to bias relevance); use `@AIContext` for filterable metadata. No manual indexing code needed.
- Choose backends by config alone: `ai.providers.embedding-provider`, `ai.providers.llm-provider`, and `ai.vector-db.type` — your Java search code (`AISearchRequest`) stays identical local vs. cloud.
- Keep a `smoke`-profile override (H2 + `memory` vector + stub providers) so CI and laptops run without external services.
- Read `AISearchResponse.getMaxScore()` to normalize per-result scores for ranking/threshold display.
