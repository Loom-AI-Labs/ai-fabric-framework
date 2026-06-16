# Subscription Management Hub (Simple)

> One-line: the "start here" subscription platform — the same semantic search, NL→action orchestration, governed actions, and async indexing as the full hub, but configured entirely in YAML and trimmed to the core modules.

## What it builds
The same subscription domain (plans, subscriptions, addresses, users) and the same REST/AI surface as `sub-management-hub`, with one deliberate difference: entities are **not** annotated — the AI entity contract is declared in `ai-entity-config.yml` instead. This makes it the cleaner template for teams that prefer config over entity annotations and don't need relationship-query or behavior analytics. Key HTTP entry points mirror the full hub: `/api/subscriptions` (`POST /subscribe`, `POST /{id}/upgrade|downgrade|unsubscribe`), `POST /api/subscriptions/query` (NL → orchestration), `GET /api/subscriptions/plans/ai/search` (semantic search), `POST /api/subscriptions/plans/search`, `/api/ai/debug/indexing`, `/api/demo/indexing`, and `/api/auth`.

## AI Fabric capability showcased
This is the reference for **config-driven (YAML) entity indexing** — the action + indexing + search stack of the full hub, minus annotations and minus the optional modules. Use it to learn the core flow before adding the kitchen-sink features.

## AI Fabric modules used
- `ai-fabric-starter` — full autoconfig + `@EnableAIInfrastructure`.
- `ai-fabric-provider-cohere` — Cohere LLM provider (`command-r-plus`).
- `ai-fabric-vector-lucene` — local Lucene vector store.

Dropped relative to `sub-management-hub`: `ai-fabric-relationship-query` and `ai-fabric-behavior`. All declared under `io.github.loom-ai-labs`, version `0.2.1`.

## Configuration
From `src/main/resources/application.yml`:

```yaml
ai:
  enabled: true
  config:
    default-file: ai-entity-config.yml   # entities defined here, not via annotations
  providers:
    llm-provider: cohere
    embedding-provider: simple           # deterministic local embeddings
    cohere:
      enabled: ${COHERE_ENABLED:false}
      model: command-r-plus
  vector-db:
    type: lucene
  pii-detection:
    enabled: false
    mode: DETECT_ONLY
```

The matching `ai-entity-config.yml` declares the `subscription-plan` entity (`auto-embedding`, `searchable-fields` with weights for `name`/`description`, `metadata-fields` for price/tier/features) — the YAML equivalent of the full hub's `@AICapable`/`@AISearchable` annotations. The offline **smoke** profile (from `smoke-support`) overrides `llm-provider`/`embedding-provider` to `smoke` and `vector-db.type` to `memory`.

## How it's wired in Java
- `@EnableAIInfrastructure` on `SubscriptionManagementHubApplication` bootstraps the framework; entity capabilities are loaded from `ai-entity-config.yml` (no `@AICapable` on the JPA classes).
- `ai.fabric.core.AICoreService` — `performSearch(AISearchRequest)` backs `/plans/ai/search`.
- `ai.fabric.service.AICapabilityService` — entity capability introspection for the debug controller.
- `ai.fabric.indexing.*` — `IndexingCoordinator`, `IndexingQueueService`, `AsyncIndexingWorker`, `BatchIndexingWorker` drive embedding/reindex (same classes as the full hub).
- `ai.fabric.intent.orchestration.{RAGOrchestrator, OrchestrationContext, OrchestrationResult}` — NL routing at `/api/subscriptions/query`.
- `@AIAction`/`@ActionExecute`/`@ActionAllowed`/`@ActionConfirmation`/`@Param` — the subscription actions, identical in shape to the full hub.

Semantic plan search through `AICoreService` (config-defined `subscription-plan` entity), then re-hydrated from the repository:

```java
// src/main/java/com/subscription/hub/controller/PlanAiSearchController.java
@GetMapping("/search")
public ResponseEntity<Map<String, Object>> search(@RequestParam String q,
                                                   @RequestParam(defaultValue = "5") int limit) {
    AICoreService aiCoreService = aiCoreServiceProvider.getIfAvailable();
    if (aiCoreService == null) {
        return ResponseEntity.ok(Map.of("enabled", false, "query", q));
    }
    AISearchRequest request = AISearchRequest.builder()
        .entityType("subscription-plan")
        .query(q.trim())
        .limit(Math.max(1, Math.min(limit, 50)))
        .threshold(0.0)
        .build();
    AISearchResponse response = aiCoreService.performSearch(request);
    // ... map each result's entityId back to a SubscriptionPlan via planRepository
}
```

## Request flow
1. NL request hits `POST /api/subscriptions/query`; `NaturalLanguageController` builds an `OrchestrationContext` and calls `RAGOrchestrator.orchestrate(...)`.
2. The orchestrator routes to retrieval over the config-defined `subscription-plan` embeddings or to an `@AIAction` for mutations.
3. For an action: `@ActionAllowed` authorizes, optional `@ActionConfirmation` prompts, then `@ActionExecute` runs.
4. Entity writes enqueue async (re)indexing through the `IndexingCoordinator`/`AsyncIndexingWorker`.
5. `GET /api/subscriptions/plans/ai/search` returns ranked plan matches straight from `AICoreService.performSearch`.

## Run it
Offline (no keys):
`mvn -pl sub-management-hub-simple -f examples/real-apps/pom.xml spring-boot:run -Dspring-boot.run.profiles=smoke`

Sample request (default port 8080):
```bash
curl -s "localhost:8080/api/subscriptions/plans/ai/search?q=cheapest%20plan%20with%20priority%20support&limit=5"
```

For real: set `COHERE_ENABLED=true` and `COHERE_API_KEY=...` and drop the `smoke` profile to use Cohere + Lucene.

## Take it to your own app
- Prefer YAML config (`ai-entity-config.yml` + `ai.config.default-file`) when you want the AI contract decoupled from your JPA classes — the runtime behavior matches the annotation path.
- Start from this trimmed module set (`starter` + a provider + `vector-lucene`) and add `relationship-query`/`behavior` only when you actually need them — see `sub-management-hub` for the full version.
- The search + indexing + action code is identical to the full hub, so you can migrate from YAML to `@AICapable` annotations (or vice versa) without touching controllers.
- Drive semantic search with `AISearchRequest.builder().entityType(...).query(...).threshold(...)` and re-hydrate domain rows by `entityId`.
- Keep `embedding-provider: simple` for deterministic local dev, then swap to a real provider for production via config only.
