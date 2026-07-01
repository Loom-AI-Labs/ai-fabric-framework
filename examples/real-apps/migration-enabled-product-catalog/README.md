# Migration-Enabled Product Catalog

## Scenario

This app demonstrates bulk backfill indexing for an existing product catalog using AI Fabric's
migration module.

The app starts with products in a normal database table and no pre-existing vector index. A migration
job discovers those entities, enqueues indexing work, and the final state is verified through semantic
search.

## AI Fabric Capabilities Proved

- Existing domain records can be indexed after the fact.
- Migration jobs can be started and tracked.
- Migration jobs can be paused, resumed, and canceled.
- Async indexing work is enqueued by the migration flow.
- Search after migration uses `AICoreService.performSearch`.
- Minimal annotations can be used only for migration discovery.
- Local deterministic embeddings and Lucene are enough for repeatable release evidence.

## Framework Surfaces

- `ai-fabric-migration-core`
- `ai-fabric-starter`
- `ai-fabric-vector-lucene`
- indexing queue
- `AICoreService.performSearch`
- `@AICapable` migration discovery

## Runtime Posture

Default stack:

- H2 database
- deterministic hash-based embedding provider named `simple`
- Lucene vector provider
- no external model keys
- no external vector service

The hash embedding provider is a demo provider for wiring and migration validation, not a quality
semantic embedding model.

Default port: `8095`.

## Run

From the repository root:

```bash
mvn -B -V --no-transfer-progress -f examples/real-apps/pom.xml -pl migration-enabled-product-catalog -am package
java -jar examples/real-apps/migration-enabled-product-catalog/target/migration-enabled-product-catalog-1.0.0-SNAPSHOT.jar
```

## Validate

Focused tests:

```bash
mvn -B -V --no-transfer-progress -f examples/real-apps/pom.xml -pl migration-enabled-product-catalog -am test
```

Use `requests/demo.http` to run the migration scenario.

## Demo Flow

1. Seed products into the relational database.
2. Start a product migration job.
3. Poll migration progress.
4. Pause/resume/cancel as needed.
5. Verify indexed products through semantic search.

## Key Endpoints

- `POST /api/demo/seed?count=5000`
- `POST /api/migration/jobs/products/start`
- `GET /api/migration/jobs/{jobId}/progress`
- `POST /api/migration/jobs/{jobId}/pause`
- `POST /api/migration/jobs/{jobId}/resume`
- `POST /api/migration/jobs/{jobId}/cancel`
- `GET /api/products/search?q=...`

## Important Design Note

Migration needs a way to bind an `entityType` to a JPA repository. This app keeps annotations minimal:
`Product` uses `@AICapable(entityType = "product")` for migration discovery while the rest of the
indexing behavior remains framework-driven.

## What This App Does Not Cover

- Document ingestion/chunking. Use `document-ingestion-workbench`.
- Queue dead-letter operator workflow. That is currently covered by indexing module tests.
- Live cloud vector provider behavior. Use `cloud-qdrant-openai-vector-search`.
