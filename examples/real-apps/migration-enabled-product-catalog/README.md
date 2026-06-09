# Migration-Enabled Product Catalog (Real_App)

Scenario: **bulk backfill indexing** using the AI Fabric migration module, validated via semantic search.

Default stack (no external services): **H2 + SimpleHash embeddings + Lucene vector DB**.

## What this app proves

- Seed a product catalog into the DB **without** manually indexing anything
- Start a **resumable migration job** that enqueues async indexing work
- Verify the end result via **semantic search** (`AICoreService.performSearch`)

## Important note (minimal annotations)

Migration needs a way to bind an `entityType` to a JPA repository. Today the migration module discovers entities via `@AICapable`.

This app keeps it minimal: `Product` has only `@AICapable(entityType = "product")` to enable migration discovery.

## Embeddings note (demo-only)

This app uses an in-app `EmbeddingProvider` named `simple` (hash-based, fully offline) to avoid large model files.
It is meant to validate wiring and migration flows, not to provide high-quality semantic embeddings.

## Run

1) Build framework artifacts:

`cd ai-infrastructure-module && mvn -DskipTests install`

2) Run the app:

`cd examples/real-apps/migration-enabled-product-catalog && mvn -DskipTests package && java -jar target/*.jar`

App port: `8095`

## Demo endpoints

- `POST /api/demo/seed?count=5000`
- `POST /api/migration/jobs/products/start`
- `GET /api/migration/jobs/{jobId}/progress`
- `POST /api/migration/jobs/{jobId}/pause`
- `POST /api/migration/jobs/{jobId}/resume`
- `POST /api/migration/jobs/{jobId}/cancel`
- `GET /api/products/search?q=...`

Use `requests/demo.http` to run the scenario.
