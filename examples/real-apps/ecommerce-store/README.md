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

Runtime chat/RAG integration can use OpenAI for **LLM + embeddings** when those features are enabled.

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
