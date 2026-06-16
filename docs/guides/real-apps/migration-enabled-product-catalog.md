# Migration-Enabled Product Catalog

> One-line: bulk-backfill an existing product catalog into the vector store with async, pausable migration jobs.

## What it builds
A product catalog (sku, name, description, category, price) that already has thousands of rows and now needs to become searchable. Rather than re-saving every row to trigger indexing, it uses AI Fabric's migration module to run a controlled, rate-limited backfill into a local Lucene index — then exposes semantic product search. Key endpoints: `@RequestMapping("/api/products")` with `GET /`, `GET /count`, `GET /{id}`, `GET /search?q=&limit=&threshold=`; `@RequestMapping("/api/migration/jobs")` with `POST /products/start`, `GET /` (list), `GET /{jobId}/progress`, `POST /{jobId}/pause`, `POST /{jobId}/resume`, `POST /{jobId}/cancel`; and `@RequestMapping("/api/demo")` with `POST /seed`.

## AI Fabric capability showcased
This is the reference example for the **migration module** — `DataMigrationService` running asynchronous, batched, rate-limited backfill jobs that index pre-existing data into the vector store, with progress tracking and pause/resume/cancel control.

## AI Fabric modules used
- `io.github.loom-ai-labs:ai-fabric-starter:0.2.1` — auto-config, `AICoreService`, search DTOs.
- `io.github.loom-ai-labs:ai-fabric-migration-core:0.2.1` — `DataMigrationService` + async job machinery.
- `io.github.loom-ai-labs:ai-fabric-vector-lucene:0.2.1` — embedded Lucene vector store (offline).

## Configuration
```yaml
ai:
  enabled: true
  config:
    default-file: ai-entity-config.yml
  providers:
    llm-provider: none
    embedding-provider: simple        # deterministic offline embeddings
  vector-db:
    type: lucene
  service:
    features:
      enable-generation: false
  indexing:
    enabled: true
    async-worker:                     # background worker drains the index queue
      enabled: true
      fixed-delay: PT0.5S
      batch-size: 25
  migration:
    enabled: true
    default-batch-size: 500           # rows fetched per migration batch
    default-rate-limit: 600           # throttle (items/min) to protect the DB
    max-concurrent-jobs: 1
    entity-fields:
      product:
        created-at-field: "createdAt" # cursor field for incremental scans
```
There is no `application-smoke.yml`: `simple`/`lucene`/`none` already run offline. The `smoke` profile additionally activates the shared `smoke-support` stub beans.

## How it's wired in Java
- `@EnableAIInfrastructure` on `MigrationEnabledProductCatalogApplication` enables AI Fabric.
- `Product` is annotated `@AICapable(entityType = "product")`, so the migration worker knows how to index each row.
- `SimpleHashEmbeddingProvider implements EmbeddingProvider` supplies offline embeddings (`embedding-provider: simple`).
- `MigrationJobsController` injects `ai.fabric.migration.service.DataMigrationService` and uses the migration domain types — `MigrationRequest`, `MigrationFilters`, `MigrationJob`, `MigrationProgress` — to start and monitor jobs. `ProductCatalogService` uses `AICoreService` for the search side.

```java
// src/main/java/com/ai/fabric/realapps/migrationcatalog/web/MigrationJobsController.java
@PostMapping("/products/start")
public MigrationJob startProductsJob(@RequestParam(value = "batchSize", defaultValue = "500") Integer batchSize,
                                     @RequestParam(value = "rateLimit", defaultValue = "600") Integer rateLimit,
                                     @RequestParam(value = "reindexExisting", defaultValue = "false") Boolean reindexExisting,
                                     @RequestParam(value = "createdBy", defaultValue = "demo") String createdBy,
                                     @RequestBody(required = false) StartFilters filters) {
    MigrationFilters migrationFilters = filters != null ? filters.toFilters() : null;
    MigrationRequest request = MigrationRequest.builder()
        .entityType("product")
        .batchSize(batchSize)
        .rateLimit(rateLimit)
        .reindexExisting(reindexExisting)
        .filters(migrationFilters)
        .createdBy(createdBy)
        .build();
    return dataMigrationService.startMigration(request);
}
```

## Request flow
1. `POST /api/migration/jobs/products/start` builds a `MigrationRequest` (entity type `product`, batch size, rate limit, optional `MigrationFilters`) and calls `dataMigrationService.startMigration(...)`, which returns a `MigrationJob` and runs asynchronously.
2. The migration worker scans `Product` rows in batches, embeds each via the `simple` provider, and writes vectors into the Lucene index (throttled by the rate limit).
3. `GET /api/migration/jobs/{jobId}/progress` returns a `MigrationProgress` snapshot; `pause`/`resume`/`cancel` control the running job.
4. Once indexed, `GET /api/products/search?q=...` runs semantic search via `AICoreService` over the backfilled vectors.

## Run it
Offline (no keys):
`mvn -pl migration-enabled-product-catalog -f examples/real-apps/pom.xml spring-boot:run -Dspring-boot.run.profiles=smoke`

Seed, backfill, watch progress, then search:
```bash
curl -s -X POST "http://localhost:8095/api/demo/seed?count=2000"
JOB=$(curl -s -X POST "http://localhost:8095/api/migration/jobs/products/start?batchSize=500&rateLimit=600" | sed 's/.*"id":"\([^"]*\)".*/\1/')
curl -s "http://localhost:8095/api/migration/jobs/$JOB/progress"
curl -s "http://localhost:8095/api/products/search?q=wireless%20headphones&limit=5"
```

For real: nothing external is required (offline `simple`/`lucene`). To run against real backends, swap `embedding-provider` and `vector-db.type` to a cloud provider/vector module and configure its `ai.providers.*` block; the migration controller code is unchanged.

## Take it to your own app
- Use `DataMigrationService.startMigration(MigrationRequest.builder()...)` to backfill existing tables instead of re-saving every row.
- Tune `batchSize` + `rateLimit` (and `ai.migration.*` defaults) to throttle indexing so a large backfill doesn't overwhelm your DB or embedding provider.
- Pass `MigrationFilters` to scope a job, and set `reindexExisting` to control whether already-indexed rows are reprocessed.
- Poll `MigrationProgress` via `GET /{jobId}/progress` and wire `pause`/`resume`/`cancel` for operability on long-running jobs.
- Configure a cursor field (`ai.migration.entity-fields.<entity>.created-at-field`) so incremental scans are deterministic.
