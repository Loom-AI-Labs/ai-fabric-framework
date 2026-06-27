# Chat Capabilities Demo (AI Fabric)

v0 quickstart (golden path):
- `../../docs/V0_QUICKSTART.md`

This Real App demonstrates:
- **Chat sessions**: multi-turn context + turn recording + conversation lifecycle APIs (get/list/delete)
- **Product catalog + RAG**: CRUD products, index into **Lucene**, and retrieve via chat (OpenAI embeddings + LLM)
- **Push ingestion runtime**: `ai-fabric-data-sync` endpoints accept product/policy/review payloads from the
  separate `ecommerce-store` customer-owned app
- **Runtime vector probe**: `/api/runtime/vector-search` shows raw AI Fabric retrieval evidence for pushed data
- **Actions**: `create_purchase_order` action executed from chat
- **Curated modes** (commerce pack): `navigator`, `navigator_deep`, `executor`, `cart_assistant`

## Run

```bash
mvn -f ai-infrastructure-module/pom.xml install
mvn -f examples/real-apps/chat-capabilities-demo/pom.xml spring-boot:run
```

The runtime listens on `http://localhost:8097` by default so it can run beside the domain-only
`ecommerce-store` app on `http://localhost:8096`.

No-key local runtime:

```bash
java -jar target/chat-capabilities-demo-1.0.0-SNAPSHOT.jar \
  --spring.profiles.active=smoke \
  --server.port=8097 \
  --management.server.port=0
```

Local customer-app to runtime proof:

```bash
CONNECTOR_INDEXING_ENABLED=true \
CONNECTOR_INDEXING_RUNTIME_BASE_URL=http://localhost:8097 \
java -jar ../ecommerce-store/target/ecommerce-store-1.0.0-SNAPSHOT.jar \
  --spring.profiles.active=smoke \
  --server.port=8096 \
  --management.server.port=0 \
  --spring.datasource.url='jdbc:h2:mem:ecommerce_runtime_proof;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE' \
  --app.demo.seed-data=false
```

Then use `../ecommerce-store/requests/demo.runtime.http` to push data and verify
`GET http://localhost:8097/api/runtime/vector-search`.

## UI migration (request contract + positions)

See `Final_Documentation/Development_Guides/CHAT_CAPABILITIES_UI_MIGRATION_GUIDE.md`.

## API Docs (Swagger)

- Swagger UI: `http://localhost:8097/swagger-ui/index.html`
- OpenAPI JSON: `http://localhost:8097/v3/api-docs`

## OpenAI Setup

This app expects OpenAI for **LLM + embeddings** (required for RAG + intent extraction + actions).

```bash
export OPENAI_ENABLED=true
export OPENAI_API_KEY="..."
export OPENAI_MODEL="gpt-4o-mini"                         # optional
export OPENAI_EMBEDDING_MODEL="text-embedding-3-small"     # optional
export OPENAI_EMBEDDING_DIMENSIONS="512"                  # recommended for Lucene (max 1024)
```

Then open `requests/demo.http`.

## CORS (for https://ai-fabric.dev demo UI)

If you are calling this API from a browser-based frontend on another domain, set:

```bash
export CORS_ALLOWED_ORIGINS="https://ai-fabric.dev"
```
