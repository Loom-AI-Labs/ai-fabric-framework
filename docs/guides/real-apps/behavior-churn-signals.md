# Behavior Churn Signals

> One-line: turns a raw user-event stream into churn-risk and sentiment insights per user.

## What it builds
A Spring Boot service that ingests behavioral events (logins, payment failures, complaints, cancellations, upgrades) and produces a `BehaviorInsights` record per user containing a churn-risk score, sentiment label/score, trend, segment, and recommended actions. Your app keeps owning the event data; AI Fabric's `BehaviorAnalysisService` reads it through an SPI you implement and calls the LLM to score it. Key endpoints (under `BehaviorOpsController` `@RequestMapping("/api/behavior")` and `DemoController` `@RequestMapping("/api/demo")`):

- `POST /api/behavior/analyze/{userId}` — analyze one user on demand
- `POST /api/behavior/process-next` — analyze the next user with new events
- `GET /api/behavior/insights`, `GET /api/behavior/insights/{userId}`, `GET /api/behavior/insights/{userId}/summary`
- `POST /api/demo/seed` — seed demo events

## AI Fabric capability showcased
This is the reference example for the **behavior analytics** capability: deriving churn + sentiment insights from a user event stream. It shows the SPI pattern where your data stays in your DB and AI Fabric pulls events through a provider interface you supply.

## AI Fabric modules used
- `ai-fabric-behavior` — `BehaviorAnalysisService`, insights entity/repository, event SPI.
- `ai-fabric-provider-starter` — LLM provider wiring + `@EnableAIInfrastructure`.

## Configuration
```yaml
ai:
  vector-db:
    type: false
  service:
    features:
      enable-embeddings: false
      enable-search: false
      enable-generation: true   # behavior analysis needs LLM generation only
  providers:
    llm-provider: behavior-stub # local stub bean (see How it's wired)
  behavior:
    enabled: true
    mode: LIGHT                 # analysis depth
```
Only generation is enabled — there is no embedding or vector-search dependency. `llm-provider: behavior-stub` selects the in-app deterministic provider so the app runs with no keys. The shared `smoke` profile (from the `smoke-support` dependency) overrides `llm-provider` to `smoke` and `vector-db.type` to `memory`, redirecting all AI selectors at the bundled offline stubs.

## How it's wired in Java
- `@EnableAIInfrastructure` on `BehaviorChurnSignalsApplication` bootstraps the AI Fabric beans.
- `ai.fabric.behavior.spi.ExternalEventProvider` — you implement this so the framework can read your events. `DbExternalEventProvider` maps `AppBehaviorEvent` rows to `ai.fabric.behavior.model.ExternalEvent` / `UserEventBatch`.
- `ai.fabric.behavior.service.BehaviorAnalysisService` — `analyzeUser(userId)` / `processNextUser()` run the analysis and persist `ai.fabric.behavior.entity.BehaviorInsights` via `BehaviorInsightsRepository`.
- `ai.fabric.provider.AIProvider` — `BehaviorStubLlmProvider` is a local stub returning deterministic scoring JSON for offline runs.

```java
// src/main/java/com/ai/fabric/realapps/behavior/spi/DbExternalEventProvider.java
@Component
@RequiredArgsConstructor
public class DbExternalEventProvider implements ExternalEventProvider {

    private final AppBehaviorEventRepository eventRepository;
    private final BehaviorInsightsRepository insightsRepository;

    @Override
    public List<ExternalEvent> getEventsForUser(String userId, LocalDateTime since, LocalDateTime until) {
        LocalDateTime effectiveUntil = until != null ? until : LocalDateTime.now();
        LocalDateTime effectiveSince = since != null ? since : effectiveUntil.minusDays(30);
        return eventRepository.findWindow(userId, effectiveSince, effectiveUntil).stream()
            .map(this::toExternalEvent)
            .toList();
    }

    @Override
    public UserEventBatch getNextUserEvents() {
        // pick the next user whose latest event is newer than its last analysis...
        List<ExternalEvent> events = getEventsForUser(userId, null, null);
        return UserEventBatch.builder()
            .userId(userId).events(events).totalEventCount(events.size())
            .userContext(Map.of("source", "db", "eventCount", events.size()))
            .build();
    }
}
```

## Request flow
1. `POST /api/behavior/analyze/{userId}` hits `BehaviorOpsController`.
2. The controller calls `BehaviorAnalysisService.analyzeUser(userId)`.
3. The service calls your `ExternalEventProvider.getEventsForUser(...)` (here `DbExternalEventProvider`) to pull the event window from your DB.
4. The service builds a prompt and calls the configured `AIProvider` (`behavior-stub` offline) which returns scoring JSON.
5. The service maps that into a `BehaviorInsights`, persists it via `BehaviorInsightsRepository`, and returns it as the HTTP response.

`process-next` is the same path but the service uses `getNextUserEvents()` to choose the user.

## Run it
Offline (no keys):
`mvn -pl behavior-churn-signals -f examples/real-apps/pom.xml spring-boot:run -Dspring-boot.run.profiles=smoke`

Then:
```bash
curl -s -X POST http://localhost:8097/api/demo/seed
curl -s -X POST http://localhost:8097/api/behavior/process-next
curl -s http://localhost:8097/api/behavior/insights
```

For real: drop the `smoke` profile and keep `llm-provider: behavior-stub`, or swap it for a real provider (e.g. set `ai.providers.llm-provider` to your provider and supply that provider's API key) so `BehaviorAnalysisService` calls a real model instead of the deterministic stub.

## Take it to your own app
- Implement `ExternalEventProvider` to feed your existing event tables into AI Fabric without copying data into the framework.
- Let `BehaviorAnalysisService` own scoring and persistence (`BehaviorInsights` + `BehaviorInsightsRepository`); your controller stays thin.
- Use `processNextUser()` to drive a scheduled/background analysis loop over users with fresh events.
- Enable only `enable-generation` and set `vector-db.type: false` when you need LLM reasoning but no embeddings/vector store — keeps the footprint minimal.
- Drop in a local `AIProvider` bean to develop and test deterministically offline, then switch via `ai.providers.llm-provider` for production.
