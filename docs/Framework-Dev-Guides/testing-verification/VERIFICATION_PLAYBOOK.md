# Verification Playbook (Connector Demo + Runtime)

This document is a repeatable checklist to verify the runnable 2-service demo:

- **Customer Connector** (domain APIs + `POST /actions/execute`)
- **AI Fabric Runtime** (chat orchestration, action execution, and vector indexing)

It focuses on the two failure classes we hit most often:

- Runtime actions not loading (so everything becomes `OUT_OF_SCOPE`)
- Indexing not happening (vectors stay at `0`)

For vector-provider deployments that are not the ecommerce demo, use:

- `scripts/verify-vector-deployment.sh`

That script verifies live connector/runtime health, runtime artifact alignment, and a real data-sync upsert/delete roundtrip against the deployed vector backend.

It has now been proven end to end against these live profiles:

- Qdrant Cloud in `PLATFORM_MANAGED` using existing-cluster reuse
- Weaviate Cloud in `EXTERNAL_EXISTING`
- Pinecone in `PLATFORM_MANAGED`
- Milvus through Zilliz Cloud in `PLATFORM_MANAGED`

The GitHub Actions deployment and suite workflows intentionally force read-only deployment verification. They do not run the write roundtrip path. The write path remains available when you run the scripts directly and explicitly set `VERIFY_WRITE=true`.

Both deployment verification scripts also now support file-backed secret inputs in addition to direct env vars:

- `API_KEY_FILE`
- `RUNTIME_ADMIN_API_KEY_FILE`
- `CONNECTOR_ADMIN_API_KEY_FILE`
- `PLATFORM_API_KEY_FILE`
- `PLATFORM_COOKIE_FILE`
- `PLATFORM_LOGIN_EMAIL_FILE`
- `PLATFORM_LOGIN_PASSWORD_FILE`

That is the preferred model for GitHub Actions and the platform-hosted runner. Direct env vars still work for local operator use.

For the admin-only product runner, use:

- `../deployment-operations/PLATFORM_HOSTED_DEPLOYMENT_VERIFICATION_GUIDE.md`

For deployment-scoped vectorization verification and tenant-shared isolation proof in the product UI, use:

- `../retrieval-vectorization/PLATFORM_VECTORIZATION_AND_TENANT_VERIFICATION_GUIDE.md`

For manual CI/CD execution in GitHub Actions, use:

- `./GITHUB_ACTIONS_DEPLOYMENT_VERIFICATION_GUIDE.md`
- `./GITHUB_ACTIONS_VERIFICATION_SUITE_GUIDE.md`
- `./PLATFORM_REGRESSION_AND_LIVE_ADMIN_VERIFICATION_GUIDE.md`

For direct admin-only live API regression without UI dependence, use:

- `scripts/verify-platform-admin-regression.sh`

For direct managed-provider verification of Pinecone, Qdrant Cloud, Zilliz Cloud, and Weaviate Cloud, use:

- `scripts/verify-managed-vector-providers.sh`

For local vector provider contract parity, run the shared `VectorDatabaseService` contract suite:

```bash
cd ai-infrastructure-module
mvn test -pl integration-Testing/vector-contract-tests -am
```

This is an offline gate. It covers the local memory and Lucene providers against the same lifecycle
contract: capability flags, store/get/search, metadata-filtered scans with cursors, projection flags,
update, batch remove, clear-by-entity-type isolation, and scoped-provider isolation with the same
`entityType`/`entityId` stored under two independent provider scopes.

For local real-provider parity with containers, run:

```bash
.github/scripts/run-vector-container-contracts.sh
```

This automatic release gate requires a running Docker daemon and covers Qdrant REST, Qdrant gRPC,
Weaviate, and Milvus against the same contract, including scoped-provider isolation using two
configured scopes in the same provider backend. Override images with
`TESTCONTAINERS_QDRANT_IMAGE=...`, `TESTCONTAINERS_WEAVIATE_IMAGE=...`, or
`TESTCONTAINERS_MILVUS_IMAGE=...` when validating a specific provider version. Keep Pinecone in the
managed/live provider verification path because it is a SaaS backend, not a local Testcontainers
target.

The automatic `Framework Build` GitHub Actions workflow and the manual `Framework Provider Matrix
Suite` workflow both run this as the `Vector Provider Container Contracts` job.

For Pinecone provider-live parity, run the opt-in SaaS provider suite:

```bash
cd ai-infrastructure-module
PINECONE_API_KEY=... \
PINECONE_API_HOST=https://<index-host>.pinecone.io \
PINECONE_INDEX_NAME=<index-name> \
PINECONE_LIVE_REQUIRED=true \
mvn verify -Ppinecone-live-tests -pl victor-databases/ai-fabric-vector-pinecone -am
```

This test uses an isolated namespace prefix and validates real store/fetch/search/update/clear behavior
with metadata filtering and eventual-consistency polling. If the configured Pinecone index is sparse, it
also validates sparse embedding roundtrip behavior. `PINECONE_INDEX_NAME` is optional when it can be
derived from `PINECONE_API_HOST`; otherwise provide `PINECONE_INDEX_NAME` and `PINECONE_ENVIRONMENT`.
`PINECONE_LIVE_REQUIRED=true` makes missing credentials or location configuration fail the release
gate instead of skipping the live tests.

The manual `Framework Provider Matrix Suite` GitHub Actions workflow runs this direct gate
automatically for the Pinecone matrix row before the broader application-level RealAPI suites.
Configure `PINECONE_API_HOST`, or configure both `PINECONE_INDEX_NAME` and `PINECONE_ENVIRONMENT`,
as repository variables or workflow inputs; the workflow does not include a built-in Pinecone index
default.

For deployed runtime vector readiness, run the lightweight health verifier:

```bash
RUNTIME_BASE_URL="https://<runtime>.up.railway.app" \
.github/scripts/verify-vector-readiness-health.sh
```

This checks `/actuator/health/vectorProvider` and fails unless the vector provider reports a clean
`READY` / `productionReady=true` verdict. To allow operational `WARN` states during non-release
diagnostics:

```bash
RUNTIME_BASE_URL="https://<runtime>.up.railway.app" \
VECTOR_READINESS_ALLOW_WARN=true \
.github/scripts/verify-vector-readiness-health.sh
```

The verifier also accepts `VECTOR_READINESS_URL` for a custom health URL and
`VECTOR_READINESS_JSON_FILE` for offline validation against a saved health response.

## 0) Fill These In

Set the two base URLs you are verifying:

```bash
export CONNECTOR_BASE_URL="https://<connector>.up.railway.app"
export RUNTIME_BASE_URL="https://<runtime>.up.railway.app"
```

Runtime admin endpoints are protected by default. Set the admin key used by the runtime:

```bash
export RUNTIME_ADMIN_API_KEY_HEADER="X-ADMIN-API-KEY"
export RUNTIME_ADMIN_API_KEY="<secret>"
```

Configure the runtime service with:
- `APP_ADMIN_API_KEY=<same secret>`
- Optional: `APP_ADMIN_API_KEY_HEADER=X-ADMIN-API-KEY`

Helper for adding the runtime admin header:

```bash
RUNTIME_ADMIN_CURL_HEADER=()
if [ -n "${RUNTIME_ADMIN_API_KEY:-}" ]; then
  RUNTIME_ADMIN_CURL_HEADER=(-H "${RUNTIME_ADMIN_API_KEY_HEADER:-X-ADMIN-API-KEY}: ${RUNTIME_ADMIN_API_KEY}")
fi
```

Connector/admin endpoints may use a separate key. If enabled, set:

```bash
export CONNECTOR_ADMIN_API_KEY_HEADER="X-AIFABRIC-API-KEY"
export CONNECTOR_ADMIN_API_KEY="<secret>"
```

For local-only connector demos that intentionally disable admin auth, set:

```bash
export CONNECTOR_ADMIN_AUTH_ENABLED="false"
```

## 1) Health Checks

```bash
curl -sS "${RUNTIME_BASE_URL}/actuator/health"
curl -sS "${CONNECTOR_BASE_URL}/actuator/health"
```

Expected: `{"status":"UP"}` for both.

## 2) Verify Runtime “Vector Spaces” (Entity Config Loaded)

Runtime should expose the Data Sync API and list configured vector spaces:

```bash
curl -sS "${RUNTIME_BASE_URL}/api/ai/data-sync/vector-spaces"
```

If you enabled the Generic REST Connector runtime proxy (`REST_CONNECTOR_RUNTIME_PROXY_ENABLED=true`), you can also call it via the connector:

```bash
curl -sS "${CONNECTOR_BASE_URL}/api/ai/data-sync/vector-spaces"
```

Expected (connector demo config): includes `product`, `review`, `policy`.

If this is `404`:

- Ensure `ai.data-sync.enabled=true`
- Ensure embeddings are enabled (in the demo config this is tied to `OPENAI_ENABLED=true`)

## 3) Verify Index Counts (Is Indexing Happening?)

Check the runtime’s vector index counts:

```bash
curl -sS "${RUNTIME_BASE_URL}/api/admin/indexing/overview" \
  "${RUNTIME_ADMIN_CURL_HEADER[@]}"
```

Expected: JSON with `countsByEntityType` and `totalVectors`.

If you enabled the Generic REST Connector runtime proxy (`REST_CONNECTOR_RUNTIME_PROXY_ENABLED=true`), you can also call this via the connector:

```bash
curl -sS "${CONNECTOR_BASE_URL}/api/admin/indexing/overview" \
  -H "${CONNECTOR_ADMIN_API_KEY_HEADER}: ${CONNECTOR_ADMIN_API_KEY}"
```

## 4) Connector → Runtime Indexing (Product)

This verifies the event-based indexing flow: connector writes a product, then the runtime gets an upsert.

1) Create a product in the connector:

```bash
curl -sS -X POST "${CONNECTOR_BASE_URL}/api/products" \
  -H "Content-Type: application/json" \
  -d '{
    "sku": "SKU_VERIFY_1",
    "name": "Verification Laptop",
    "description": "Created by verification playbook to test indexing.",
    "category": "gaming",
    "tags": "laptop,gaming",
    "imageUrl": "https://example.com/image",
    "price": 1299.00,
    "currency": "USD",
    "inStockQty": 5
  }'
```

2) Wait briefly, then re-check runtime counts:

```bash
sleep 1
curl -sS "${RUNTIME_BASE_URL}/api/admin/indexing/overview" \
  "${RUNTIME_ADMIN_CURL_HEADER[@]}"
```

3) Inspect indexed vectors (paged):

```bash
curl -sS "${RUNTIME_BASE_URL}/api/admin/indexing/vectors?entityType=product&offset=0&limit=50" \
  "${RUNTIME_ADMIN_CURL_HEADER[@]}"
```

Expected: a vector with `entityId: "SKU_VERIFY_1"`.

If the product is created in the connector but runtime counts do not change:

- Ensure connector indexing is enabled:
  `CONNECTOR_INDEXING_ENABLED=true`

- Ensure the connector points at the runtime with an absolute URL (must include scheme):
  `CONNECTOR_INDEXING_RUNTIME_BASE_URL=https://...`

- Confirm runtime Data Sync endpoint is reachable from the connector (connector logs will show failures).

## 5) Connector → Runtime Indexing (Review)

Create a review in the connector:

```bash
curl -sS -X POST "${CONNECTOR_BASE_URL}/api/reviews" \
  -H "Content-Type: application/json" \
  -d '{
    "userId": "u1",
    "sku": "SKU_VERIFY_1",
    "rating": 5,
    "text": "Great performance and build quality."
  }'
```

Then verify `review` count increments:

```bash
sleep 1
curl -sS "${RUNTIME_BASE_URL}/api/admin/indexing/overview" \
  "${RUNTIME_ADMIN_CURL_HEADER[@]}"
```

And inspect review vectors:

```bash
curl -sS "${RUNTIME_BASE_URL}/api/admin/indexing/vectors?entityType=review&offset=0&limit=50" \
  "${RUNTIME_ADMIN_CURL_HEADER[@]}"
```

## 6) Reset / Clear (For Repeatable Testing)

### 6.1 Clear runtime vectors (runtime endpoint)

```bash
curl -sS -X POST "${RUNTIME_BASE_URL}/api/admin/migration/clear?confirm=true" \
  "${RUNTIME_ADMIN_CURL_HEADER[@]}"
```

### 6.2 Reset connector demo (connector endpoint, clears connector DB and can clear runtime vectors)

Demo reset endpoints are protected by default. Configure the service with:

- `APP_ADMIN_API_KEY=<secret>`
- Optional: `APP_ADMIN_API_KEY_HEADER=X-ADMIN-API-KEY`

Ecommerce-store compatibility aliases are also accepted:

- `CONNECTOR_ADMIN_API_KEY=<secret>`
- Optional: `CONNECTOR_ADMIN_API_KEY_HEADER=X-AIFABRIC-API-KEY`

For local-only no-key demos, opt out explicitly with `APP_ADMIN_AUTH_ENABLED=false` or
`CONNECTOR_ADMIN_AUTH_ENABLED=false`.

To allow the connector reset endpoint to clear runtime vectors too, set these on the **connector** service:

- `CONNECTOR_RUNTIME_ADMIN_API_KEY=<same as APP_ADMIN_API_KEY>`
- Optional: `CONNECTOR_RUNTIME_ADMIN_API_KEY_HEADER=X-ADMIN-API-KEY`

```bash
curl -sS -X POST "${CONNECTOR_BASE_URL}/api/admin/demo/reset" \
  -H "Content-Type: application/json" \
  -H "${CONNECTOR_ADMIN_API_KEY_HEADER}: ${CONNECTOR_ADMIN_API_KEY}" \
  -d '{ "confirm": true, "clearConnectorData": true, "clearRuntimeVectors": true }'
```

Backwards-compatible alias:

```bash
curl -sS -X POST "${CONNECTOR_BASE_URL}/api/admin/migration/clear" \
  -H "Content-Type: application/json" \
  -H "${CONNECTOR_ADMIN_API_KEY_HEADER}: ${CONNECTOR_ADMIN_API_KEY}" \
  -d '{ "confirm": true, "clearConnectorData": true, "clearRuntimeVectors": true }'
```

## 7) Action Wiring (Runtime → Connector) Quick Verification

Two practical checks:

1) **Runtime action catalog endpoint** (admin):

```bash
curl -sS "${RUNTIME_BASE_URL}/api/admin/actions/overview" \
  "${RUNTIME_ADMIN_CURL_HEADER[@]}"
```

Expected: `count > 0` and includes connector demo actions like `add_to_cart`, `list_products`, etc.

2) **Connector reachability**:
   - Ensure `ACTIONS_CONNECTOR_BASE_URL` is absolute and includes scheme.
   - Good: `https://ai-fabric-framework-production-a247.up.railway.app`
   - Bad: `ai-fabric-framework-production-a247.up.railway.app`

Common symptom in runtime logs:

- `URI is not absolute` (base URL missing `https://` / `http://`)
- `Connector service is unavailable` (wrong URL, connector down, or network failure)
