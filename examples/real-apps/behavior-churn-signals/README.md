# Behavior Churn Signals

## Scenario

This app demonstrates behavior analytics, churn/sentiment insight generation, and a retention action
workflow using AI Fabric's behavior module.

The app is fully offline by default. It uses H2, deterministic sample behavior data, and an in-app
deterministic LLM provider so the scenario is repeatable without external API keys.

## AI Fabric Capabilities Proved

- `ai.behavior.enabled=true` activates the behavior module.
- Application events can be exposed through the `ExternalEventProvider` SPI.
- `BehaviorAnalysisService` produces persisted `BehaviorInsights` per user.
- Built-in behavior analytics endpoints can query trend and rapid-decline signals.
- Retention workflow can combine behavior evidence, plan evidence, and confirmation-gated actions.
- Customer-safe recommendation output can cite stable evidence ids rather than raw internal state.

## Framework Surfaces

- `ai-fabric-behavior`
- `ai-fabric-provider-starter`
- deterministic local LLM provider
- H2-backed behavior persistence
- support code for retention studio flows

## Runtime Posture

Default runtime is local-only:

- H2 database
- deterministic local LLM
- no vector DB
- no external provider keys

Use this app to prove behavior analytics independently from RAG/vector infrastructure.

## Run

From the repository root:

```bash
mvn -B -V --no-transfer-progress -f examples/real-apps/pom.xml -pl behavior-churn-signals -am package
java -jar examples/real-apps/behavior-churn-signals/target/behavior-churn-signals-1.0.0-SNAPSHOT.jar
```

Default port: `8097`.

## Validate

Run the focused test suite:

```bash
mvn -B -V --no-transfer-progress -f examples/real-apps/pom.xml -pl behavior-churn-signals -am test
```

Use `requests/demo.http` to run the product scenario.

## Demo Flow

1. Seed users and behavior events.
2. Analyze behavior for a selected user.
3. Process queued behavior insights.
4. Query stored behavior insights.
5. Review trend and rapid-decline analytics.
6. Run the retention studio scenario to produce an evidence-backed retention recommendation.

## Key Endpoints

- `POST /api/demo/seed`
- `POST /api/behavior/analyze/{userId}`
- `POST /api/behavior/process-next`
- `GET /api/behavior/insights`
- `GET /api/behavior/analytics/rapid-decline`
- `GET /api/behavior/analytics/trend-distribution`

## What This App Does Not Cover

- Live LLM provider behavior.
- Vector search or RAG.
- Multi-tenant vector storage.

Those are covered by other real apps such as `provider-failover-lab`, `smart-faq-assistant`,
`tenant-knowledge-portal`, and `vector-readiness-playground`.
