# Subscription Management Hub (Simple)

**Scenario:** Minimal, configuration-driven AI Fabric integration (keep app code free of AI annotations).

## Setup

- Spring Boot `3.2.x`, Java `21`
- Database: H2 file (`./data/subscriptiondb`)
- AI: deterministic local embeddings + Lucene vector DB
- AI config file: `src/main/resources/ai-entity-config.yml`

## Build + Run

1) Install the framework artifacts:

`cd ../../ai-infrastructure-module && mvn -DskipTests install`

2) Run the app (port `8080`):

`cd examples/real-apps/sub-management-hub-simple && mvn -DskipTests clean package && java -jar target/*.jar`

## Validate Indexing + Search (Debug Endpoints)

- Check what AI/indexing beans are active:
  - `GET http://localhost:8080/api/ai/debug/indexing/components`
- Reindex seeded subscription plans (sync):
  - `POST http://localhost:8080/api/ai/debug/indexing/reindex/plans?mode=sync`
- Query plans via vector search:
  - `GET http://localhost:8080/api/ai/debug/indexing/search/plans?q=premium&limit=5`
- End-to-end demo (mutate + index + search):
  - `POST http://localhost:8080/api/ai/debug/indexing/demo?mode=sync`

## Validate (App-Level Scenarios)

These endpoints are more “product-like” and validate that the framework integration works without exposing internal indexing/queue types.

- Reindex plans (explicit, config-first trigger):
  - `POST http://localhost:8080/api/demo/indexing/reindex/plans`
- AI plan recommendation (returns scores + hydrated plans):
  - `GET http://localhost:8080/api/subscriptions/plans/ai/search?q=team%20collaboration%20api%20access&limit=3`
- Plan search endpoint used by the app (returns plans only, falls back to DB contains if AI isn’t available):
  - `POST http://localhost:8080/api/subscriptions/plans/search?query=priority%20support&limit=3`

### Ready-to-run request file

Use `examples/real-apps/sub-management-hub-simple/requests/demo.http`.

### Queue-based (Async) Validation

- Enqueue indexing:
  - `POST http://localhost:8080/api/ai/debug/indexing/reindex/plans?mode=async`
- Process one worker tick manually (if you don’t want to wait for scheduling):
  - `POST http://localhost:8080/api/ai/debug/indexing/queue/run-once?strategy=async`
- Queue status:
  - `GET http://localhost:8080/api/ai/debug/indexing/queue`
