# Chat Capabilities Demo

## Scenario

This app is the broad AI Fabric chat/runtime showcase. It combines commerce chat, chat sessions,
catalog RAG, local actions, confirmation-required write actions, curated commerce modes, and
runtime data-sync endpoints that can receive pushed data from the separate `ecommerce-store` domain
fixture.

Use it when you want to show AI Fabric as an application runtime rather than a single-purpose
library integration.

## AI Fabric Capabilities Proved

- Chat-session storage, turn recording, conversation listing, and conversation deletion.
- Conversation-aware orchestration across multiple turns.
- Product CRUD plus AI Fabric indexing into Lucene.
- RAG over product/catalog data.
- Curated commerce modes: `navigator`, `navigator_deep`, `executor`, and `cart_assistant`.
- Local `@AIAction` handlers for catalog, cart, order, review, support, address, shipment, and account
  workflows.
- Action confirmation for write actions.
- Pending-action yes/no flow.
- Confirmation-interceptor behavior for cancellation/retention paths.
- Runtime data-sync endpoints for product/policy/review payloads from an external domain app.
- Runtime vector probe endpoint that exposes raw retrieval evidence for pushed data.
- Browser-oriented request contract and CORS configuration.

## Framework Surfaces

- `ai-fabric-starter`
- `ai-fabric-chat-session`
- `ai-fabric-data-sync`
- `ai-fabric-rag`
- `ai-fabric-vector-lucene`
- local action annotations and confirmation policy
- curated commerce mode configuration
- Spring AI/OpenAI provider path when enabled

## Runtime Posture

Two runtime modes are useful:

- **Smoke/no-key mode:** boots locally with deterministic providers for release smoke checks.
- **Full chat/RAG mode:** uses OpenAI for LLM and embeddings so intent extraction, RAG generation,
  and action selection use a real model.

Default app port: `8097`.

## Public Demo App

This app backs the public AI Shopping Experience demo:

- Demo UI: `https://ai-fabric.dev/demos/ai-fabric-framework`
- Expected backend runtime: `https://ai-fabric-chat-capabilities-demo.46.224.145.148.sslip.io`
- Chat API: `POST /api/chat/query`
- Demo readiness API: `/api/demo`

The public demo is designed to prove AI Fabric as a commerce runtime:

1. Start from no indexed evidence and show that RAG-backed answers should not be claimed yet.
2. Seed evidence in stages: products, reviews, policies, coupons, tickets, then full.
3. Run natural-language shopping prompts against the current evidence stage.
4. Inspect retrieved documents and vector proof rather than faking context.
5. Use curated commerce positions only when the UI/user selects them.
6. Exercise cart, checkout, support, return, coupon, and policy actions with confirmation where needed.
7. Keep browser users isolated through `shopping-demo-user-*` ownership and scheduled cleanup.

The stage model is:

- `empty`: no catalog evidence is ready.
- `products`: product catalog is indexed.
- `reviews`: products plus review evidence are indexed.
- `policies`: products, reviews, and policy evidence are indexed.
- `coupons`: discount/coupon evidence is available.
- `full`: support-ticket evidence is available and the full shopping demo is ready.

Use `GET /api/demo/health` and `GET /api/demo/readiness` before demoing. They expose build metadata,
RAG/data-sync/vector posture, stage counts, vector counts, retrieval proof, and warnings.

## Run

### Docker With Released AI Fabric

Build from the repository root. This uses the latest released AI Fabric version declared by the app
(`0.3.2` currently) and does not copy or install `ai-infrastructure-module`:

```bash
docker build \
  -f examples/real-apps/chat-capabilities-demo/Dockerfile \
  --build-arg AI_FABRIC_VERSION=0.3.2 \
  -t ai-fabric-chat-capabilities-demo:0.3.2 \
  examples/real-apps
```

No-key smoke runtime:

```bash
docker run --rm -p 8097:8097 \
  -e SPRING_PROFILES_ACTIVE=smoke \
  ai-fabric-chat-capabilities-demo:0.3.2
```

Full OpenAI-backed runtime:

```bash
docker run --rm -p 8097:8097 \
  -e OPENAI_ENABLED=true \
  -e OPENAI_API_KEY="$OPENAI_API_KEY" \
  -e OPENAI_MODEL=gpt-4o-mini \
  -e OPENAI_EMBEDDING_MODEL=text-embedding-3-small \
  -e OPENAI_EMBEDDING_DIMENSIONS=512 \
  ai-fabric-chat-capabilities-demo:0.3.2
```

Health endpoint:

```bash
curl http://localhost:8097/actuator/health
curl http://localhost:8097/api/demo/health | jq
curl http://localhost:8097/api/demo/readiness | jq
```

Seed the demo in stages:

```bash
curl -fsS -X POST http://localhost:8097/api/demo/reset \
  -H 'Content-Type: application/json' \
  -d '{"confirm":true,"clearVectors":true,"clearIndexingQueue":true}' | jq

curl -fsS -X POST http://localhost:8097/api/demo/stages/products | jq
curl -fsS -X POST http://localhost:8097/api/demo/stages/reviews | jq
curl -fsS -X POST http://localhost:8097/api/demo/stages/policies | jq
curl -fsS -X POST http://localhost:8097/api/demo/stages/coupons | jq
curl -fsS -X POST http://localhost:8097/api/demo/stages/tickets | jq
curl -fsS -X POST http://localhost:8097/api/demo/stages/full | jq
```

If demo controls are protected, send the configured header:

```bash
curl -fsS -X POST http://localhost:8097/api/demo/stages/full \
  -H "X-DEMO-API-KEY: $APP_DEMO_CONTROLS_API_KEY" | jq
```

### Local Source Checkout

For framework development, install local framework artifacts and start the app from the repository
root:

```bash
mvn -B -V --no-transfer-progress -f ai-infrastructure-module/pom.xml install
mvn -B -V --no-transfer-progress -f examples/real-apps/chat-capabilities-demo/pom.xml spring-boot:run
```

No-key local runtime:

```bash
java -jar examples/real-apps/chat-capabilities-demo/target/chat-capabilities-demo-1.0.0-SNAPSHOT.jar \
  --spring.profiles.active=smoke \
  --server.port=8097 \
  --management.server.port=0
```

## OpenAI Setup

Full chat/RAG/action extraction expects OpenAI for LLM and embeddings:

```bash
export OPENAI_ENABLED=true
export OPENAI_API_KEY="..."
export OPENAI_MODEL="gpt-4o-mini"
export OPENAI_EMBEDDING_MODEL="text-embedding-3-small"
export OPENAI_EMBEDDING_DIMENSIONS="512"
```

## External Runtime Proof With `ecommerce-store`

Run this app as the AI Fabric runtime on `8097`, then run `ecommerce-store` on `8096` with event
indexing pointed at this runtime:

```bash
CONNECTOR_INDEXING_ENABLED=true \
CONNECTOR_INDEXING_RUNTIME_BASE_URL=http://localhost:8097 \
java -jar examples/real-apps/ecommerce-store/target/ecommerce-store-1.0.0-SNAPSHOT.jar \
  --spring.profiles.active=smoke \
  --server.port=8096 \
  --management.server.port=0 \
  --spring.datasource.url='jdbc:h2:mem:ecommerce_runtime_proof;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE' \
  --app.demo.seed-data=false
```

Use `../ecommerce-store/requests/demo.runtime.http` to push data and verify:

- `GET /api/ai/data-sync/vector-spaces`
- `GET /api/runtime/vector-search`

## Validate

Focused test/package command:

```bash
mvn -B -V --no-transfer-progress -f examples/real-apps/pom.xml -pl chat-capabilities-demo -am test
```

Use `requests/demo.http` for the chat/product/action flow.

## API Docs

- Swagger UI: `http://localhost:8097/swagger-ui/index.html`
- OpenAPI JSON: `http://localhost:8097/v3/api-docs`

## UI And Browser Integration

For browser calls from another origin:

```bash
export CORS_ALLOWED_ORIGINS="https://ai-fabric.dev"
```

The chat request contract and UI positions are described in:

- `docs/Framework-Dev-Guides/ui-clients/CHAT_CAPABILITIES_UI_MIGRATION_GUIDE.md`

## Demo Deployment Env Vars

The public deployment normally uses:

- `PORT=8097`
- `OPENAI_ENABLED=true`
- `OPENAI_API_KEY=<secret>`
- `OPENAI_MODEL=gpt-4o-mini`
- `OPENAI_EMBEDDING_MODEL=text-embedding-3-small`
- `OPENAI_EMBEDDING_DIMENSIONS=512`
- `AI_DATA_SYNC_ENABLED=true`
- `APP_ADMIN_AUTH_ENABLED=true`
- `APP_ADMIN_API_KEY=<secret>`
- `APP_ADMIN_API_KEY_HEADER=X-ADMIN-API-KEY`
- `CORS_ALLOWED_ORIGINS=https://ai-fabric.dev`
- `JAVA_OPTS=-Xms256m -Xmx768m`
- `APP_DEMO_CONTROLS_ENABLED=true`
- Optional demo-control protection: `APP_DEMO_CONTROLS_API_KEY`, `APP_DEMO_CONTROLS_API_KEY_HEADER`.
- Optional cleanup tuning: `APP_DEMO_CLEANUP_TTL=PT24H`, `APP_DEMO_CLEANUP_CRON=0 17 * * * *`.

Docker build metadata is baked into `/app/build-info.properties` from `SOURCE_COMMIT` or
`BUILD_COMMIT`, `SOURCE_BRANCH` or `BUILD_BRANCH`, and `BUILD_TIME`. `GET /api/demo/health` should
reflect the deployed commit before live verification.

Suggested deployment values:

- `git_repository=Loom-AI-Labs/ai-fabric-framework.git`
- `git_branch=main`
- `base_directory=/examples/real-apps`
- `dockerfile_location=/chat-capabilities-demo/Dockerfile`
- `ports_exposes=8097`

## What This App Does Not Cover

- DB-backed dynamic action registration. Use `db-action-registry-lab`.
- Customer-owned runtime boundary in isolation. Use `customer-runtime-demo`.
- Provider fallback diagnostics. Use `provider-failover-lab`.
- Vector lifecycle/admin readiness. Use `vector-readiness-playground`.
