# Subscription Management Hub

> One-line: the kitchen-sink subscription platform — annotation-driven semantic search, NL→action orchestration, governed subscription actions, async indexing, and PII validation, all in one app.

## What it builds
A subscription platform (plans, subscriptions, addresses, users) where domain entities are AI-annotated so they are automatically embedded, indexed, and searchable, and where natural-language requests are routed to governed actions. The AI feature spans semantic plan search, NL→query orchestration, and write actions (subscribe/upgrade/downgrade/cancel/update-address) with confirmation and PII checks. Key HTTP entry points: `/api/subscriptions` (`POST /subscribe`, `POST /{id}/upgrade|downgrade|unsubscribe`), `POST /api/subscriptions/query` (NL → orchestration), `GET /api/subscriptions/plans/ai/search` (semantic search), `POST /api/subscriptions/plans/search`, the indexing debug surface under `/api/ai/debug/indexing` and `/api/demo/indexing`, and guest auth under `/api/auth`.

## AI Fabric capability showcased
This is the **most complete reference app**: it combines annotation-assisted indexing (`@AICapable`/`@AISearchable` on JPA entities), semantic search via `AICoreService`, the full governed action model, async/batch indexing workers, and PII detection — the "everything on" configuration.

## AI Fabric modules used
- `ai-fabric-starter` — full autoconfig + `@EnableAIInfrastructure`.
- `ai-fabric-relationship-query` — relationship-aware NL query support.
- `ai-fabric-behavior` — behavior/analytics signals (e.g. churn-risk fields).
- `ai-fabric-provider-cohere` — Cohere LLM provider (`command-r-plus`).
- `ai-fabric-vector-lucene` — local Lucene vector store.

All declared under `io.github.loom-ai-labs`, version `0.2.1`.

## Configuration
From `src/main/resources/application.yml`:

```yaml
ai:
  config:
    default-file: ai-entity-config.yml   # supplemental entity config
  providers:
    llm-provider: cohere
    embedding-provider: simple           # deterministic local embeddings
    cohere:
      enabled: ${COHERE_ENABLED:false}
      model: command-r-plus
  vector-db:
    type: lucene
  behavior:
    enabled: false                       # behavior module present, off by default
    mode: LIGHT
  pii-detection:
    enabled: false                       # PII service present, off by default
    mode: DETECT_ONLY
```

The offline **smoke** profile (from the `smoke-support` module) overrides `llm-provider`/`embedding-provider` to `smoke` and `vector-db.type` to `memory`, so the hub boots without a Cohere key.

## How it's wired in Java
- `@EnableAIInfrastructure` on `SubscriptionManagementHubApplication` bootstraps everything.
- **Entities carry the AI contract**: `SubscriptionPlan` is `@AICapable(autoEmbedding=true, indexingStrategy=ASYNC)` with `@AISearchable(weight=...)` on `name`/`description` and `@AIContext` on price/tier/features — this is the annotation-driven path (vs. the simple variant's YAML).
- `ai.fabric.core.AICoreService` — runs `performSearch(AISearchRequest)` for `/plans/ai/search`.
- `ai.fabric.service.AICapabilityService` + `ai.fabric.config.AIEntityConfigurationLoader` — introspect configured entities (used by the debug controller).
- `ai.fabric.indexing.*` — `IndexingCoordinator`, `IndexingQueueService`, `AsyncIndexingWorker`, `BatchIndexingWorker`, `IndexingStrategy` drive embedding/reindex.
- `ai.fabric.intent.orchestration.{RAGOrchestrator, OrchestrationContext, OrchestrationResult}` — NL query routing at `/api/subscriptions/query`.
- `@AIAction`/`@ActionExecute`/`@ActionAllowed`/`@ActionConfirmation`/`@Param` — subscription actions; `ai.fabric.privacy.pii.PIIDetectionService` validates address input.

A real governed action that also runs PII detection:

```java
// src/main/java/com/subscription/hub/action/handler/UpdateAddressActionHandler.java
@AIAction(name = "update_address", description = "Update billing or shipping address",
          category = "subscription", accessMode = ActionAccessMode.WRITE_ONLY,
          requiresConfirmation = true)
public class UpdateAddressActionHandler extends BaseActionHandler {
    @Autowired(required = false) private PIIDetectionService piiDetectionService;

    @ActionExecute
    public ActionResult execute(/* @Param street/city/state/postalCode/country */ ActionContext context) {
        Address address = Address.builder().streetAddress(streetAddress).city(city).build();
        if (piiDetectionService != null) {
            var piiResult = piiDetectionService.detectAndProcess(addressString);
            address.setIsValidated(piiResult.isPiiDetected() == false);
        }
        var subscription = subscriptionService.updateAddress(UUID.fromString(subscriptionId), parsedType, address);
        return ActionResult.builder().success(true).message("Your address has been updated successfully").build();
    }
}
```

## Request flow
1. NL request hits `POST /api/subscriptions/query`; `NaturalLanguageController` builds an `OrchestrationContext` and calls `RAGOrchestrator.orchestrate(...)`.
2. The orchestrator classifies intent — for a search-style ask it retrieves over the Lucene-indexed `subscription-plan` embeddings; for a mutation it selects an `@AIAction`.
3. `@ActionAllowed` authorizes (e.g. must have an active subscription), then `@ActionConfirmation` may prompt; `@ActionExecute` runs the write (PII-validating address input where relevant).
4. Entity writes trigger async (re)indexing via the `IndexingCoordinator`/`AsyncIndexingWorker` so search stays current.
5. `OrchestrationResult` / `ActionResult` is returned; `/api/subscriptions/plans/ai/search` returns ranked plan matches directly from `AICoreService`.

## Run it
Offline (no keys):
`mvn -pl sub-management-hub -f examples/real-apps/pom.xml spring-boot:run -Dspring-boot.run.profiles=smoke`

Sample request (default port 8081):
```bash
curl -s "localhost:8081/api/subscriptions/plans/ai/search?q=team%20plan%20with%20priority%20support&limit=5"
```

For real: set `COHERE_ENABLED=true` and `COHERE_API_KEY=...` and drop the `smoke` profile to use Cohere + Lucene. Flip `ai.behavior.enabled` / `ai.pii-detection.enabled` to `true` to activate those modules.

## Take it to your own app
- Declare the AI surface on the JPA entity itself: `@AICapable` + `@AISearchable(weight)` + `@AIContext` means no separate config to keep in sync.
- Pick `IndexingStrategy.ASYNC` for high-write entities so embedding happens off the request path via `AsyncIndexingWorker`.
- Use `AICoreService.performSearch(AISearchRequest.builder()...)` for semantic search and re-hydrate full rows from your repo by `entityId`.
- Combine governance + privacy: a single `@AIAction` can call `PIIDetectionService.detectAndProcess(...)` inside `@ActionExecute` before committing.
- Keep optional capabilities (behavior, PII) wired but config-gated (`enabled: false`) so you can switch them on per environment.
