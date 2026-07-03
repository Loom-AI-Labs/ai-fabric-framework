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

## What This App Does Not Cover

- DB-backed dynamic action registration. Use `db-action-registry-lab`.
- Customer-owned runtime boundary in isolation. Use `customer-runtime-demo`.
- Provider fallback diagnostics. Use `provider-failover-lab`.
- Vector lifecycle/admin readiness. Use `vector-readiness-playground`.
