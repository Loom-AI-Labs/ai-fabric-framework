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

## 0) Fill These In

Set the two base URLs you are verifying:

```bash
export CONNECTOR_BASE_URL="https://<connector>.up.railway.app"
export RUNTIME_BASE_URL="https://<runtime>.up.railway.app"
```

Optional: if you configured runtime admin auth (`APP_ADMIN_API_KEY`), set:

```bash
export RUNTIME_ADMIN_API_KEY_HEADER="X-ADMIN-API-KEY"
export RUNTIME_ADMIN_API_KEY="<secret>"
```

If you want to protect runtime admin endpoints (`/api/admin/*`), configure the runtime service with:
- `APP_ADMIN_API_KEY=<same secret>`
- Optional: `APP_ADMIN_API_KEY_HEADER=X-ADMIN-API-KEY`

Optional helper: only send the runtime admin header when a key is set:

```bash
RUNTIME_ADMIN_CURL_HEADER=()
if [ -n "${RUNTIME_ADMIN_API_KEY:-}" ]; then
  RUNTIME_ADMIN_CURL_HEADER=(-H "${RUNTIME_ADMIN_API_KEY_HEADER:-X-ADMIN-API-KEY}: ${RUNTIME_ADMIN_API_KEY}")
fi
```

Optional: if you enabled connector admin auth (`connector.auth.api-key`), set:

```bash
export CONNECTOR_ADMIN_API_KEY_HEADER="X-AIFABRIC-API-KEY"
export CONNECTOR_ADMIN_API_KEY="<secret>"
```

Optional: if you want connector demo reset endpoints to require the same API key as `/actions/execute`:

```bash
export CONNECTOR_ADMIN_AUTH_ENABLED="true"
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

This endpoint is protected only when:

- `connector.auth.api-key` is set, and
- `connector.admin.auth.enabled=true`

To enable protection for demo resets, set:

- `CONNECTOR_ADMIN_AUTH_ENABLED=true`

If you keep it public (default), you can omit the connector API key header.

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
