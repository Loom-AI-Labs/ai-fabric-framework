# Event-Based Indexing Plan (Connector → Runtime Data Sync)

## Goal

When a domain entity changes in the **Ecommerce Store** (the domain API app on `:8096`), automatically push an upsert/delete to the **AI Fabric Runtime** Data Sync API (`:8097`) so the vector index stays current **without manual Data Sync calls**.

## Non-Goals (for this demo iteration)

- No generic “CDC for all entities”. Start with **products** only.
- No durable job queue / guaranteed delivery semantics (retry/backoff can be added later).
- Data-sync requests include a verified platform auth context so AI Fabric can grant
  `data-sync:upsert` / `data-sync:delete` through its fail-closed access path.

## Current State (before)

- Creating/updating products in the connector updates only the connector H2 DB.
- Indexing happens only when someone manually calls runtime Data Sync (`/api/ai/data-sync/*`).

## Target State (after)

- Creating/updating/deleting a product/policy/review in the connector triggers an **after-commit event**.
- An async listener consumes the event and calls runtime Data Sync:
  - `POST /api/ai/data-sync/upsert` for create/update/stock updates
  - `POST /api/ai/data-sync/delete` for deletes
  - SKU changes emit `delete(oldSku)` + `upsert(newSku)`

## Components to Add (Connector)

1. **Indexing properties**
   - `connector.indexing.enabled` (default: `false`)
   - `connector.indexing.runtime-base-url` (default: `http://ai-fabric-runtime:8097` for Docker Compose)
   - Optional auth header + value (forward-compatible)

2. **Domain indexing events**
   - `ProductUpsertIndexingEvent`
   - `ProductDeleteIndexingEvent`

3. **Runtime Data Sync client**
   - Minimal HTTP client that posts JSON payloads to runtime Data Sync endpoints.
   - Safe-by-default: failures are logged; connector requests should still succeed.

4. **Event listener**
   - `@TransactionalEventListener(phase = AFTER_COMMIT)` to ensure DB changes are committed.
   - `@Async` to avoid adding latency to domain API calls.

5. **Publish events from ProductService**
   - On create/update/delete/stock update publish the appropriate indexing event(s).

## Configuration / Deployment Changes

- In Docker Compose, enable connector indexing by default (dev convenience):
  - `CONNECTOR_INDEXING_ENABLED=true`
  - `CONNECTOR_INDEXING_RUNTIME_BASE_URL=http://ai-fabric-runtime:8097`

## Validation Steps

1. Bring up the stack:
   - `OPENAI_ENABLED=true OPENAI_API_KEY=... docker compose ... up --build`
2. Create a product via connector API:
   - `POST http://localhost:8096/api/products`
3. Query runtime chat for that product:
   - `POST http://localhost:8097/api/chat/query` asking for the new product by name/category.
4. Delete the product via connector API:
   - `DELETE http://localhost:8096/api/products/{id}`
5. Confirm runtime retrieval no longer returns it (vector search result set changes).

## Files (expected)

- `examples/real-apps/ecommerce-store/src/main/java/.../indexing/*`
- `examples/real-apps/ecommerce-store/src/main/resources/application.yml`
- `examples/real-apps/ecommerce-store/deploy/docker/docker-compose.yml`
