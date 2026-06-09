# Verification Playbook (Ecommerce Store + REST Connector + Runtime)

This playbook is for verifying a deployed 3-service stack:
- **Ecommerce Store** (domain APIs)
- **Generic REST Connector** (action routing + optional runtime proxy)
- **AI Fabric Runtime** (chat + RAG + vector DB)

## Quick URLs (UI-Friendly)

Replace:
- `{store}` = ecommerce-store base URL
- `{rest}` = rest connector base URL
- `{runtime}` = runtime base URL (optional if you proxy runtime via `{rest}`)

### Ecommerce Store

- `GET {store}/actuator/health`
- `POST {store}/api/admin/demo/reset` (confirm required; supports `clearConnectorData`, `clearRuntimeVectors`)
- `POST {store}/api/admin/demo/clear` (confirm required; eventful deletes for product/policy/review)
- `POST {store}/api/admin/migration/clear` (legacy alias for reset)

### REST Connector (routing verification)

- `GET {rest}/actuator/health`
- `GET {rest}/api/admin/overview`
- `GET {rest}/api/admin/actions/overview`
- `GET {rest}/api/admin/actions/{actionId}`

### Runtime (direct verification)

- `GET {runtime}/actuator/health`
- `GET {runtime}/api/admin/actions/overview` (action catalog loaded)
- `GET {runtime}/api/admin/indexing/overview` (vector counts by entityType)
- `GET {runtime}/api/admin/indexing/vectors?entityType=product&offset=0&limit=50`

### Runtime (via REST connector proxy, if enabled)

- `GET {rest}/api/chat/*` (proxy to runtime chat API)
- `GET {rest}/api/ai/data-sync/vector-spaces`
- `POST {rest}/api/ai/data-sync/upsert`
- `POST {rest}/api/ai/data-sync/delete`
- `POST {rest}/api/ai/data-sync/batch`
- `GET {rest}/api/admin/indexing/overview`
- `GET {rest}/api/admin/indexing/vectors?entityType=product&offset=0&limit=50`
- `POST {rest}/api/admin/migration/clear` (proxy to runtime vector clear; confirm required)

## Scripted Verification (recommended)

Run with your own local or deployed URLs:

```bash
STORE_BASE_URL="http://localhost:8096" \
REST_CONNECTOR_BASE_URL="http://localhost:8082" \
RUNTIME_BASE_URL="http://localhost:8097" \
API_KEY="..." \
./scripts/verify-ecommerce-deployment.sh
```

Optional (write checks: create/delete product, add_to_cart, and verify indexing count changes):

```bash
VERIFY_WRITE=true ./scripts/verify-ecommerce-deployment.sh
```
