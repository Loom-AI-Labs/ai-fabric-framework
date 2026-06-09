# Cloud Qdrant + OpenAI Vector Search (Real_App)

Scenario: **production-like semantic search** using **Postgres** for domain storage, **Qdrant** as the external vector database, and **OpenAI** for embeddings.

This app is intentionally simple:
- No custom framework wiring.
- Indexing/embedding intent is defined via annotations (`@AICapable`, `@AISearchable`, `@AIContext`).
- Provider + enablement config is driven by `src/main/resources/application.yml` and `src/main/resources/ai-entity-config.yml`.

## What this app proves

- Real provider + real vector DB setup works (OpenAI embeddings + Qdrant gRPC)
- Config-driven indexing/search (`ai.config.default-file: ai-entity-config.yml`)
- Apps can index on write (`AICapabilityService.processEntityForAI`) and search via `AICoreService.performSearch`

## Prerequisites

- Docker (for Postgres + Qdrant) OR equivalent external services
- OpenAI API key

## Run

1) Install the framework artifacts locally:

`cd ai-infrastructure-module && mvn -DskipTests install`

2) Start Postgres + Qdrant:

`cd examples/real-apps/cloud-qdrant-openai-vector-search && docker compose up -d`

If you previously ran this app with an older Qdrant image, recreate volumes to avoid client/server incompatibilities:

`cd examples/real-apps/cloud-qdrant-openai-vector-search && docker compose down -v && docker compose up -d`

3) Run the app (port `8098`):

```bash
export AI_PROVIDERS_OPENAI_API_KEY="..."
cd examples/real-apps/cloud-qdrant-openai-vector-search
mvn -DskipTests package
java -jar target/*.jar
```

## Configuration (minimal)

- OpenAI:
  - `AI_PROVIDERS_OPENAI_API_KEY` (required)
- Qdrant:
  - `AI_PROVIDERS_QDRANT_HOST` (defaults to `localhost`)
  - `AI_PROVIDERS_QDRANT_GRPC_PORT` (defaults to `6334`)
  - `AI_PROVIDERS_QDRANT_API_KEY` (optional; needed for Qdrant Cloud)

Use `requests/demo.http` to run the scenario.
