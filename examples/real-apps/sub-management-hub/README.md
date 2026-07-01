# Subscription Management Hub Advanced

## Scenario

This app demonstrates an explicit subscription-plan integration style. It still keeps
`ai-entity-config.yml` as the primary source of truth, but it also shows optional annotations and
local action handlers for subscription workflows.

Use this app to compare config-first integration with annotation-assisted integration.

## AI Fabric Capabilities Proved

- Annotation-assisted indexing over subscription plans.
- Config-driven indexing/search parity with the simple subscription app.
- Deterministic local embeddings plus Lucene vector search.
- Explicit reindex and search endpoints.
- Subscription actions such as subscribe, upgrade, downgrade, cancel, and address update.
- `@ActionAllowed` authorization hooks.
- `@ActionConfirmation` for write actions.
- Async indexing queue validation.

## Framework Surfaces

- `ai-fabric-starter`
- `ai-fabric-provider-spring-ai`
- `ai-fabric-vector-lucene`
- indexing queue
- local `@AIAction` handlers
- action authorization and confirmation annotations
- config-driven entity metadata

## Runtime Posture

Default runtime:

- Java 21
- Spring Boot 4.1.0
- H2
- deterministic local embeddings
- Lucene vector DB
- no external model key required

Default port: `8081`.

## Run

From the repository root:

```bash
mvn -B -V --no-transfer-progress -f examples/real-apps/pom.xml -pl sub-management-hub -am package
java -jar examples/real-apps/sub-management-hub/target/subscription-management-hub-1.0.0-SNAPSHOT.jar
```

## Validate

Focused tests/package:

```bash
mvn -B -V --no-transfer-progress -f examples/real-apps/pom.xml -pl sub-management-hub -am test
```

Use `requests/demo.http` for ready-to-run calls.

## App-Level Scenario Endpoints

- `POST /api/demo/indexing/reindex/plans`
- `GET /api/subscriptions/plans/ai/search?q=team%20collaboration%20api%20access&limit=3`
- `POST /api/subscriptions/plans/search?query=priority%20support&limit=3`

## Debug/Indexing Endpoints

- `GET /api/ai/debug/indexing/components`
- `POST /api/ai/debug/indexing/reindex/plans?mode=sync`
- `GET /api/ai/debug/indexing/search/plans?q=enterprise&limit=5`
- `POST /api/ai/debug/indexing/demo?mode=sync`

## Async Queue Validation

- `POST /api/ai/debug/indexing/reindex/plans?mode=async`
- `POST /api/ai/debug/indexing/queue/run-once?strategy=async`
- `GET /api/ai/debug/indexing/queue`

## What This App Does Not Cover

- Minimal no-annotation onboarding. Use `sub-management-hub-simple`.
- DB-backed action registry. Use `db-action-registry-lab`.
- Tenant-scoped retrieval/deletion. Use `tenant-knowledge-portal`.
