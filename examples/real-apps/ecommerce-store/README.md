# Ecommerce Store

## Role

This app is a prior deployed customer-domain API fixture retained as reference material. It is not a
new ADR 0005 expansion target.

It exposes ecommerce domain endpoints for products, carts, orders, coupons, policies, reviews, and
demo reset operations. It does not expose `/actions/execute`; AI Fabric connector/runtime behavior is
proved by running this app beside `chat-capabilities-demo` or another runtime.

## Scenario

A customer-owned ecommerce service keeps its own domain model and APIs. Product, policy, and review
changes can emit AI Fabric data-sync requests to a separate runtime, where those records become
searchable through AI Fabric vector search.

## AI Fabric Capabilities Proved

- Customer-owned app can remain outside the AI Fabric runtime process.
- Product/policy/review writes can emit after-commit indexing events.
- Event indexing can target a runtime data-sync endpoint.
- Runtime vector search can find created records and stop finding deleted records.
- Demo reset/clear endpoints support repeatable migration/runtime proof.
- Public anonymous/authz reference logic exists for ecommerce runtime policy experiments.

## Framework Surfaces

- data-sync client shape
- customer-domain boundary
- event-based indexing producer
- optional integration with `chat-capabilities-demo` runtime
- admin reset/clear support for repeatable demos

## Runtime Posture

Default runtime is local H2 and seeded demo data. Runtime integration is optional and points to
another app/process.

Default port: `8096`.

## Run

From this app folder:

```bash
mvn -B -V --no-transfer-progress spring-boot:run
```

Then open:

- Swagger UI: `http://localhost:8096/swagger-ui/index.html`
- Health: `http://localhost:8096/actuator/health`

## Request Files

- `requests/demo.connector.http`: domain API examples.
- `requests/demo.runtime.http`: runtime integration shape.

## Demo Reset And Migration Clear

Maintenance endpoints:

- `POST /api/admin/demo/reset`
- `POST /api/admin/demo/clear`
- `POST /api/admin/migration/clear`

Requests require:

```json
{"confirm": true}
```

Admin endpoints are protected by default:

```bash
export APP_ADMIN_API_KEY="..."
export APP_ADMIN_API_KEY_HEADER="X-ADMIN-API-KEY"
```

Compatibility aliases are also accepted:

- `CONNECTOR_ADMIN_API_KEY`
- `CONNECTOR_ADMIN_API_KEY_HEADER`

For local-only no-key demos, opt out explicitly:

```bash
export APP_ADMIN_AUTH_ENABLED=false
```

## Event-Based Indexing

Enable event indexing:

```bash
export CONNECTOR_INDEXING_ENABLED=true
export CONNECTOR_INDEXING_RUNTIME_BASE_URL=http://localhost:8097
```

Indexing calls are sent to `CONNECTOR_INDEXING_RUNTIME_BASE_URL`, which can point at an AI Fabric
runtime data-sync endpoint or compatible connector target.

## Optional Runtime Proof With `chat-capabilities-demo`

Start the AI Fabric runtime:

```bash
java -jar ../chat-capabilities-demo/target/chat-capabilities-demo-1.0.0-SNAPSHOT.jar \
  --spring.profiles.active=smoke \
  --server.port=8097 \
  --management.server.port=0
```

Start this domain API with event indexing enabled:

```bash
CONNECTOR_INDEXING_ENABLED=true \
CONNECTOR_INDEXING_RUNTIME_BASE_URL=http://localhost:8097 \
java -jar target/ecommerce-store-1.0.0-SNAPSHOT.jar \
  --spring.profiles.active=smoke \
  --server.port=8096 \
  --management.server.port=0 \
  --spring.datasource.url='jdbc:h2:mem:ecommerce_runtime_proof;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE' \
  --app.demo.seed-data=false
```

Expected proof:

1. Create a product in ecommerce.
2. Ecommerce emits an AI Fabric data-sync upsert request.
3. Runtime vector search returns the product.
4. Delete the product in ecommerce.
5. Runtime vector search no longer returns it.

The top-level `examples/real-apps/README.md` includes a full curl script for this proof.

## Seed Data

The app seeds a small demo catalog, including `SKU-0001`, `SKU-0002`, and coupon `SAVE10`.

Disable seed data:

```bash
export APP_DEMO_SEED_DATA=false
```

## CORS

For browser-based frontends:

```bash
export CORS_ALLOWED_ORIGINS="https://your-ui.example"
```

## What This App Does Not Cover

- AI Fabric action execution endpoint. Use `db-action-registry-lab`, `customer-runtime-demo`, or
  `chat-capabilities-demo`.
- AI Fabric runtime orchestration by itself. Use `chat-capabilities-demo`.
- Relay/platform packaging. That belongs outside this framework repo.
