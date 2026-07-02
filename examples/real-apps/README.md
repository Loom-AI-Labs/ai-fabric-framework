# AI Fabric Real Apps

These standalone Spring Boot applications are public examples used to validate AI Fabric Framework capabilities in realistic product shapes.

For the detailed capability matrix and per-app proof notes, see
[`REAL_APP_CAPABILITIES.md`](REAL_APP_CAPABILITIES.md).

The apps are intentionally scenario-focused:

- `smart-faq-assistant`: offline FAQ search using a deterministic local embedding provider and optional RAG.
- `migration-enabled-product-catalog`: migration/backfill indexing with local H2, hash embeddings, and Lucene.
- `privacy-first-customer-facing-support`: PII detection and redaction workflow.
- `relationship-query-crm-insights`: natural language relationship query with an offline deterministic LLM.
- `behavior-churn-signals`: behavior analytics and churn/sentiment insight flow with an offline deterministic LLM.
- `chat-capabilities-demo`: chat-session storage and conversation-aware orchestration.
- `customer-runtime-demo`: customer-owned domain fixture with data-sync, tenant-scoped retrieval, and governed actions.
- `db-action-registry-lab`: DB-backed connector action registration, approval, discovery, execution, and deregistration.
- `document-ingestion-workbench`: trusted document upload, preview, indexing, reindex, and delete lifecycle.
- `it-support-action-bot`: provider-only action orchestration path.
- `mcp-operations-assistant`: governed MCP-style operations tool execution.
- `provider-failover-lab`: provider routing/fallback diagnostics and transient-input policy evidence.
- `sub-management-hub-simple`: config-driven indexing setup using local deterministic embeddings by default.
- `ai-fabric-account-resolver`: account-resolution demo using governed actions, readiness blockers, and local deterministic embeddings by default.
- `tenant-knowledge-portal`: tenant-scoped knowledge search, catalog visibility, role-limited actions, and deletion.
- `vector-readiness-playground`: vector provider lifecycle/admin readiness evidence.
- `ecommerce-store`: prior deployed domain API fixture retained as reference material.
- `cloud-qdrant-openai-vector-search`: cloud vector search shape using OpenAI, Postgres, and Qdrant.

## Public Demo Apps

`behavior-churn-signals` backs the public AI Fabric Behavior Signals demo at
`https://ai-fabric.dev/demos/ai-fabric-behavior-signals`.

To run the backend locally from the repository root:

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
