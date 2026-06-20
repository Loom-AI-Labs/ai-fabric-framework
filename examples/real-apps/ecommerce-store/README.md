# Ecommerce Store

This real app is the domain API fixture used by connector/runtime examples. It exposes products, carts, orders, coupons, policies, reviews, and demo reset endpoints on port `8096`.

The domain API exposes only domain endpoints such as `/api/products`, `/api/carts`, and `/api/orders`. It does not expose `/actions/execute`.

## Run

From this app folder:

```bash
mvn -DskipTests spring-boot:run
```

Then:
- Domain API Swagger UI: `http://localhost:8096/swagger-ui/index.html`
- Health: `http://localhost:8096/actuator/health`

Requests file:
- Domain API: `requests/demo.connector.http`
- Runtime integration shape: `requests/demo.runtime.http`

## Demo Reset / Migration Clear (Domain API)

UI-facing maintenance endpoints:
- `POST /api/admin/demo/reset` (preferred)
- `POST /api/admin/demo/clear` (eventful; deletes indexed entities via service methods so delete-index events fire)
- `POST /api/admin/migration/clear` (legacy alias)

Both require a JSON body with at least:
- `{"confirm": true}`

Optional: protect these endpoints with an API key:
```bash
export CONNECTOR_ADMIN_AUTH_ENABLED=true
export CONNECTOR_ADMIN_API_KEY="..."
export CONNECTOR_ADMIN_API_KEY_HEADER="X-AIFABRIC-API-KEY"
```

## Event-Based Indexing (Products/Policies/Reviews)

The domain API can automatically index **product/policy/review** changes into a runtime or connector endpoint using after-commit event listeners.

Enable or disable with:

```bash
export CONNECTOR_INDEXING_ENABLED=true
```

Indexing calls are sent to `CONNECTOR_INDEXING_RUNTIME_BASE_URL`, which can point at a REST connector or runtime data-sync endpoint in an integration environment.

## Optional Runtime Setup

For local no-key proof, run the AI Fabric runtime app separately:

```bash
java -jar ../chat-capabilities-demo/target/chat-capabilities-demo-1.0.0-SNAPSHOT.jar \
  --spring.profiles.active=smoke \
  --server.port=8097 \
  --management.server.port=0
```

Then run this customer-owned domain API with event indexing enabled:

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

Creating or updating products, policies, and reviews will push verified data-sync requests to the
runtime. Use `requests/demo.runtime.http` to inspect `/api/ai/data-sync/*` and
`/api/runtime/vector-search`.

For the deterministic smoke profile, verify pushed products with an exact content query. The
suite-level guide at `examples/real-apps/README.md` includes the full create/search/delete/search
script and expected counts.

Runtime chat/RAG integration can also use OpenAI for **LLM + embeddings** when those features are enabled.

```bash
export OPENAI_ENABLED=true
export OPENAI_API_KEY="..."
export OPENAI_MODEL="gpt-4o-mini"
export OPENAI_EMBEDDING_MODEL="text-embedding-3-small"
export OPENAI_EMBEDDING_DIMENSIONS="512"
```

Then open `requests/demo.runtime.http`.

## Demo Seed Data (Domain API)

The domain API seeds a small demo catalog (including `SKU-0001` and `SKU-0002`) and a demo coupon `SAVE10` on first start.
Disable this with:

```bash
export APP_DEMO_SEED_DATA=false
```

## CORS

If you are calling this API from a browser-based frontend on another domain, set:

```bash
export CORS_ALLOWED_ORIGINS="https://your-ui.example"
```
