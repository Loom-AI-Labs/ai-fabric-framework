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

## Demo Backend App Architecture

This is the backend for the `aifabric` AI Shopping Experience UI. It is a single Spring Boot app that
owns both normal commerce REST APIs and the AI Fabric orchestration runtime used by the browser chat.

Backend dependencies:

- Spring Boot Web, Data JPA, Validation, Actuator, OpenAPI, H2, and Lombok.
- AI Fabric modules: `ai-fabric-starter`, `ai-fabric-curated-commerce`,
  `ai-fabric-provider-spring-ai`, `ai-fabric-chat-session`, `ai-fabric-data-sync`,
  `ai-fabric-governance`, `ai-fabric-indexing`, `ai-fabric-rag`, and
  `ai-fabric-vector-lucene`.
- `smoke-support` for deployment metadata and no-key smoke verification.

AI-enabled domain model:

- `Product`, `Policy`, and `Review` are annotated with `@AICapable`; their searchable/context fields
  use `@AISearchable` and `@AIContext`.
- `ProductService`, `PolicyService`, and `ReviewService` use `@AIProcess` so creates, updates, and
  deletes stay synchronized with AI Fabric indexing.
- Local `@AIAction` handlers expose product lookup, cart operations, checkout, orders, support,
  returns, reviews, addresses, shipment tracking, and account lookup.
- Confirmation-required write actions use `@ActionConfirmation`, while read actions execute directly
  through `@ActionExecute`.

Providers and storage:

- Live demo generation and embeddings use OpenAI through `ai-fabric-provider-spring-ai`.
- No-key smoke runs can use the local `ChatLocalLlmProvider`.
- Vector retrieval uses the Lucene provider; H2 stores commerce data and chat-session state.
- `ai-entity-config.yml` enables product, policy, and review indexing; the staged demo APIs control
  which evidence is actually present.

Request and data flow:

1. The UI sends a chat turn to `POST /api/chat/query`, optionally with a commerce position such as
   navigator, deep search, cart assistant, or executor.
2. `CommerceModeResolver` maps the UI position to an AI Fabric orchestration mode and prompt overlay.
3. AI Fabric loads recent turns from `ai-fabric-chat-session`, resolves intent, retrieves RAG
   evidence when enabled, and selects a local action when the user asks to do work.
4. Read actions return domain facts; write actions return a confirmation card before execution.
5. Domain writes flow back through `@AIProcess` and the async indexing worker so later RAG searches can
   retrieve the updated evidence.
6. The response returns generated answer text, retrieved documents, action state, suggestions, and UI
   evidence panels without the frontend inventing AI context.

## Run

### Docker With Released AI Fabric

Build from the repository root. This uses the latest released AI Fabric version declared by the app
(`0.4.0` currently) and does not copy or install `ai-infrastructure-module`:

```bash
docker build \
  -f examples/real-apps/chat-capabilities-demo/Dockerfile \
  --build-arg AI_FABRIC_VERSION=0.4.0 \
  -t ai-fabric-chat-capabilities-demo:0.4.0 \
  examples/real-apps
```

No-key smoke runtime:

```bash
docker run --rm -p 8097:8097 \
  -e SPRING_PROFILES_ACTIVE=smoke \
  ai-fabric-chat-capabilities-demo:0.4.0
```

Full OpenAI-backed runtime:

```bash
docker run --rm -p 8097:8097 \
  -e OPENAI_ENABLED=true \
  -e OPENAI_API_KEY="$OPENAI_API_KEY" \
  -e OPENAI_MODEL=gpt-4o-mini \
  -e OPENAI_EMBEDDING_MODEL=text-embedding-3-small \
  -e OPENAI_EMBEDDING_DIMENSIONS=512 \
  ai-fabric-chat-capabilities-demo:0.4.0
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
