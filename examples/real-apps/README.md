# AI Fabric Real Apps

These standalone Spring Boot applications are public examples used to validate AI Fabric Framework capabilities in realistic product shapes.

For the detailed capability matrix and per-app proof notes, see
[`REAL_APP_CAPABILITIES.md`](REAL_APP_CAPABILITIES.md).

The apps are intentionally scenario-focused:

- `smart-faq-assistant`: offline FAQ search using a deterministic local embedding provider and optional RAG.
- `migration-enabled-product-catalog`: migration/backfill indexing with local H2, hash embeddings, and Lucene.
- `privacy-first-customer-facing-support`: PII detection and redaction workflow.
- `relationship-query-crm-insights`: natural language relationship query with an offline deterministic LLM.
- `behavior-churn-signals`: behavior analytics and churn/sentiment insight flow with deterministic local or live OpenAI-backed LLM analysis.
- `chat-capabilities-demo`: chat-session storage, conversation-aware orchestration, staged RAG readiness, and the AI Shopping Experience demo.
- `ai-fabric-live-data-sync`: annotation-driven JPA create/update/delete synchronization with vector revision and live RAG proof.
- `customer-runtime-demo`: customer-owned domain fixture with data-sync, tenant-scoped retrieval, and governed actions.
- `db-action-registry-lab`: DB-backed connector action registration, approval, discovery, execution, and deregistration.
- `document-ingestion-workbench`: trusted document upload, preview, indexing, reindex, and delete lifecycle.
- `it-support-action-bot`: provider-only action orchestration path.
- `mcp-operations-assistant`: governed MCP-style operations tool execution.
- `provider-failover-lab`: provider routing/fallback diagnostics and transient-input policy evidence.
- `sub-management-hub-simple`: config-driven indexing setup using local deterministic embeddings by default.
- `ai-fabric-account-resolver`: account-resolution demo using profile reads, policy RAG, chat memory, and governed resolver actions.
- `tenant-knowledge-portal`: AI Fabric Tenant Guard demo for tenant-scoped search, catalog visibility, role-limited actions, and deletion.
- `vector-readiness-playground`: vector provider lifecycle/admin readiness evidence.
- `ecommerce-store`: prior deployed domain API fixture retained as reference material.
- `cloud-qdrant-openai-vector-search`: cloud vector search shape using OpenAI, Postgres, and Qdrant.

## Public `aifabric` UI Demo Coverage

Only the demos below are documented as public `aifabric` UI-backed backend apps. Other real apps in
this folder are still useful framework proofs, but they are not public `aifabric` demos today.

Live UI-backed backend apps:

| `aifabric` route | Backend app | Public backend | What it proves |
| --- | --- | --- | --- |
| `https://ai-fabric.dev/demos/ai-shopping-experience` and legacy `/demos/ai-fabric-framework` | `chat-capabilities-demo` | `https://ai-fabric-chat-capabilities-demo.46.224.145.148.sslip.io` | Commerce chat, staged RAG readiness, actions, confirmations, chat memory, data sync, and Lucene retrieval |
| `https://ai-fabric.dev/demos/ai-fabric-account-resolver` | `ai-fabric-account-resolver` | `https://ai-fabric-account-resolver.46.224.145.148.sslip.io` | Current-account resolver mode, policy RAG, read-action grounding, governed writes, and chat memory |
| `https://ai-fabric.dev/demos/ai-fabric-behavior-signals` | `behavior-churn-signals` | `https://behavior-churn-signals.46.224.145.148.sslip.io` | Behavior insights, session-scoped events, governed retention actions, and provider-posture proof |
| `https://ai-fabric.dev/demos/ai-fabric-behavior-signals/agentic-ui` | `behavior-churn-signals` | `https://behavior-churn-signals.46.224.145.148.sslip.io` | LLM-selected allowlisted home modules with backend-populated trusted props |
| `https://ai-fabric.dev/demos/ai-fabric-tenant-guard` | `tenant-knowledge-portal` | `https://ai-fabric-tenant-guard.46.224.145.148.sslip.io` | Tenant-scoped retrieval, role-aware catalog visibility, governed actions, and deletion evidence |
| `https://ai-fabric.dev/demos/ai-fabric-privacy-shield` | `privacy-first-customer-facing-support` | `https://ai-fabric-privacy-shield.46.224.145.148.sslip.io` | PII detection, redacted persistence, safe indexing, and sanitized retrieval |
| `https://ai-fabric.dev/demos/ai-fabric-live-data-sync` | `ai-fabric-live-data-sync` | `https://ai-fabric-live-data-sync.46.224.145.148.sslip.io` | Annotation-driven create/update/delete indexing, independent database/vector revision proof, and evidence-grounded chat |

UI pages that exist on `aifabric` but are not live backend clients yet:

| `aifabric` route | Runnable backend candidate | Current status |
| --- | --- | --- |
| `https://ai-fabric.dev/demos/smart-faq-assistant` | `smart-faq-assistant` | Static/explanatory UI page; backend README documents how to run the FAQ RAG service |
| `https://ai-fabric.dev/demos/document-intelligence-hub` | `document-ingestion-workbench` | Static/explanatory UI page; backend README documents how to run trusted document ingestion |
| `https://ai-fabric.dev/demos/product-discovery-engine` | Covered live by `chat-capabilities-demo` | Concept page; the live shopping demo is the current product-discovery proof |
| `https://ai-fabric.dev/demos/code-documentation-search` | None in this repo | Concept page only |
| `https://ai-fabric.dev/demos/meeting-notes-analyzer` | None in this repo | Concept page only |

Public demo backends:

- AI Shopping Experience: `https://ai-fabric-chat-capabilities-demo.46.224.145.148.sslip.io`
- Account Resolver: `https://ai-fabric-account-resolver.46.224.145.148.sslip.io`
- Behavior Signals: `https://behavior-churn-signals.46.224.145.148.sslip.io`
- Tenant Guard: `https://ai-fabric-tenant-guard.46.224.145.148.sslip.io`
- Privacy Shield: `https://ai-fabric-privacy-shield.46.224.145.148.sslip.io`
- Live Data Sync: `https://ai-fabric-live-data-sync.46.224.145.148.sslip.io`

To run the Behavior Signals backend locally from the repository root:

```bash
mvn -B -V --no-transfer-progress -f examples/real-apps/pom.xml \
  -pl behavior-churn-signals -am package

java -jar examples/real-apps/behavior-churn-signals/target/behavior-churn-signals-1.0.0-SNAPSHOT.jar
```

Then seed the demo data:

```bash
curl -fsS -X POST http://localhost:8097/api/behavior-demo/seed-and-analyze | jq
```

For Docker deployment, use
[`behavior-churn-signals/Dockerfile`](behavior-churn-signals/Dockerfile) with build context
`examples/real-apps`.

For the shopping demo, `chat-capabilities-demo` exposes staged readiness controls:

```bash
curl -fsS http://localhost:8097/api/demo/readiness | jq
curl -fsS -X POST http://localhost:8097/api/demo/stages/products | jq
curl -fsS -X POST http://localhost:8097/api/demo/stages/full | jq
```

For Account Resolver, seed personas and use the natural-language resolver endpoint:

```bash
curl -fsS -X POST http://localhost:8081/api/account-resolver/demo/seed | jq
curl -fsS -X POST http://localhost:8081/api/subscriptions/query \
  -H 'Content-Type: application/json' \
  -d '{"query":"Why cannot I place an order?","conversationId":"local-resolver-demo"}' | jq
```

## Build

Install the framework artifacts from the local checkout first:

```bash
mvn -B -V --no-transfer-progress -f ai-infrastructure-module/pom.xml install
```

Then package the real apps with their unit tests:

```bash
mvn -B -V --no-transfer-progress -f examples/real-apps/pom.xml package
```

Each app resolves the framework artifacts from the local Maven install produced by this repository.

For a focused no-key check of the customer-owned ecommerce app using AI Fabric as a separate
runtime, run:

```bash
mvn -B --no-transfer-progress -f ai-infrastructure-module/pom.xml -pl ai-fabric-core install
mvn -B --no-transfer-progress -f examples/real-apps/pom.xml \
  -pl smoke-support,chat-capabilities-demo,ecommerce-store -am clean package
```

## Runtime Notes

Most apps can boot without external API keys because they use local H2 storage and either deterministic local AI providers, deterministic local embeddings, or disabled cloud providers by default.

`cloud-qdrant-openai-vector-search` is compile-verified by default and requires Postgres, Qdrant, and OpenAI configuration before runtime smoke testing.

Every Spring Boot real app that depends on `smoke-support` exposes shared deployment metadata at:

```bash
curl -fsS http://localhost:<port>/api/demo/health | jq
```

The response includes `status`, `service`, `version`, `aiFabricVersion`, `commit`, `branch`, `builtAt`,
`startedAt`, and `checkedAt`. Dockerized demos support these optional build args:
`APP_VERSION`, `AI_FABRIC_VERSION`, `BUILD_COMMIT`, `BUILD_BRANCH`, and `BUILD_TIME`. Deployment
platform env vars such as `APP_BUILD_COMMIT`, `APP_BUILD_BRANCH`, `APP_BUILD_TIME`, `SOURCE_COMMIT`,
`GIT_COMMIT`, and `git_branch` are also read at runtime when present.

## External Runtime Proof

This local scenario proves a customer-owned app can use AI Fabric outside its own process:

- `chat-capabilities-demo` runs as the AI Fabric runtime on `8097`.
- `ecommerce-store` runs as the domain app on `8096`.
- Product writes in ecommerce emit verified data-sync requests into the runtime.
- Runtime vector search sees the product before delete and no longer sees it after delete.

Terminal 1, from the repository root:

```bash
java -jar examples/real-apps/chat-capabilities-demo/target/chat-capabilities-demo-1.0.0-SNAPSHOT.jar \
  --spring.profiles.active=smoke \
  --server.port=8097 \
  --management.server.port=0
```

Terminal 2, from the repository root:

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

Terminal 3, verify the push/index/delete loop:

```bash
sku="SKU-RT-$(date +%s)"
name="Runtime Proof Wireless Headphones"
description="Noise-cancelling over-ear headphones with 30h battery life. Built for travel, office focus, and wireless calls."
content="{\"sku\":\"$sku\",\"name\":\"$name\",\"description\":\"$description\",\"category\":\"Headphones\",\"tags\":\"wireless,noise-cancelling,audio,runtime-proof\",\"price\":199.99,\"currency\":\"USD\",\"inStockQty\":25}"
encoded_content=$(printf '%s' "$content" | jq -sRr @uri)

curl -fsS http://localhost:8097/api/ai/data-sync/vector-spaces
created=$(curl -fsS -X POST http://localhost:8096/api/products \
  -H 'Content-Type: application/json' \
  -d "$content")
product_id=$(printf '%s' "$created" | jq -r '.id')

sleep 3
curl -fsS "http://localhost:8097/api/runtime/vector-search?vectorSpace=product&q=$encoded_content&limit=5&threshold=0" \
  | jq '{returnedResults, ids: [.results[].entityId]}'

curl -fsS -X DELETE "http://localhost:8096/api/products/$product_id"

sleep 3
curl -fsS "http://localhost:8097/api/runtime/vector-search?vectorSpace=product&q=$encoded_content&limit=5&threshold=0" \
  | jq '{returnedResults, ids: [.results[].entityId]}'
```

Expected verification:

- `GET /api/ai/data-sync/vector-spaces` includes `product`.
- The first runtime vector search returns `returnedResults: 1` and the generated SKU.
- The second runtime vector search returns `returnedResults: 0`.
