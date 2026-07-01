# Subscription Management Hub Simple

## Scenario

This app demonstrates the smallest configuration-driven AI Fabric integration for subscription plan
search. App code stays free of AI annotations; indexing/search behavior comes from
`ai-entity-config.yml` and framework configuration.

Use this app as the "getting started" real app for config-first indexing.

## AI Fabric Capabilities Proved

- Config-driven entity indexing without AI annotations in app code.
- Deterministic local embeddings plus Lucene vector search.
- Explicit app-level reindex endpoint.
- AI plan recommendation endpoint that returns scores and hydrated plans.
- Product-level fallback when AI search is unavailable.
- Async queue validation through debug endpoints.

## Framework Surfaces

- `ai-fabric-starter`
- `ai-fabric-provider-spring-ai`
- `ai-fabric-vector-lucene`
- indexing queue
- config-driven entity metadata
- `AICoreService.performSearch`

## Runtime Posture

Default runtime:

- Java 21
- Spring Boot 4.1.0
- H2
- deterministic local embeddings
- Lucene vector DB
- no external model key required

Default port: `8080`.

## Run

From the repository root:

```bash
mvn -B -V --no-transfer-progress -f examples/real-apps/pom.xml -pl sub-management-hub-simple -am package
java -jar examples/real-apps/sub-management-hub-simple/target/subscription-management-hub-simple-1.0.0-SNAPSHOT.jar
```

## Validate

Focused tests/package:

```bash
mvn -B -V --no-transfer-progress -f examples/real-apps/pom.xml -pl sub-management-hub-simple -am test
```

Use `requests/demo.http` for ready-to-run calls.

## App-Level Scenario Endpoints

- `POST /api/demo/indexing/reindex/plans`
- `GET /api/subscriptions/plans/ai/search?q=team%20collaboration%20api%20access&limit=3`
- `POST /api/subscriptions/plans/search?query=priority%20support&limit=3`

## Debug/Indexing Endpoints

- `GET /api/ai/debug/indexing/components`
- `POST /api/ai/debug/indexing/reindex/plans?mode=sync`
- `GET /api/ai/debug/indexing/search/plans?q=premium&limit=5`
- `POST /api/ai/debug/indexing/demo?mode=sync`

## Async Queue Validation

- `POST /api/ai/debug/indexing/reindex/plans?mode=async`
- `POST /api/ai/debug/indexing/queue/run-once?strategy=async`
- `GET /api/ai/debug/indexing/queue`

## What This App Does Not Cover

- Optional annotation-assisted indexing. Use `sub-management-hub`.
- Chat sessions and action orchestration. Use `chat-capabilities-demo` or `it-support-action-bot`.
- Real cloud vector DB. Use `cloud-qdrant-openai-vector-search`.
