# Product_Rest Branch User Guide (Historical Draft)

Status: historical draft (updated 2026-06-19)

Release note: the current AI Fabric reactor does **not** ship a runnable Generic REST Connector
module. Treat this guide as branch history and a topology sketch. For the supported release path,
use `ai-fabric-actions-connector` in the runtime plus either `ai-fabric-relay` or a customer-owned
implementation of the Customer Connector API.

This branch turns the commerce demo into a 3-service topology:

- **AI Fabric Runtime**: chat orchestration, RAG, action dispatch, managed vector index (Data Sync push API).
- **Generic REST Connector pattern**: a planned/custom connector that implements the Customer Connector API (`POST /actions/execute`) by routing `actionId -> upstream REST endpoint` via config, and can optionally proxy a small set of runtime APIs for “single base URL” demos.
- **Ecommerce Store** (`Real_Apps/ecommerce-store`): domain APIs (products/carts/orders/reviews/policies), event-based indexing calls to runtime, demo reset tools, and a demo authz endpoint.

If you previously used `Real_Apps/chat-capabilities-connector-demo`, it has been renamed to **Ecommerce Store** and is now intended to be domain APIs first. Runtime action execution should go through a supported Customer Connector API implementation.

---

## 1) What Changed (High Signal)

### 1.1 Generic REST Connector pattern

Module:
- No runnable generic REST connector module is shipped in the current reactor.
- Current supported customer-side runtime: `ai-infrastructure-module/ai-fabric-relay`.
- Pattern guide: `docs/Framework-Dev-Guides/connectors/GENERIC_REST_API_CONNECTOR_GUIDE.md`.

Key capabilities:
- `POST /actions/execute` (runtime-compatible) routes `actionId -> {method,url/path,headers,query,body}`.
- Route templating supports defaults like `{{params.quantity|1}}` and clamps non-positive numeric values to the default (prevents `quantity=0` bugs).
- Verification/admin endpoints to confirm what routes/config are loaded at runtime.
- Optional **runtime proxy** (demo convenience) for:
  - chat API (`/api/chat/*`)
  - data-sync push API (`/api/ai/data-sync/*`)
  - indexing inspection (`/api/admin/indexing/*`)
  - migration clear (`/api/admin/migration/clear`)
- Optional **authz proxy**: `POST /api/authz/check` forwards to an upstream authz service.

### 1.2 Runtime orchestration improvements (commerce behavior)

Curated commerce pack:
- `ai-infrastructure-module/curated/ai-curated-commerce/src/main/resources/ai-curated/packs/commerce.yml`

Notable behavior:
- `navigator` mode: `actions-preferred: true` (prompting hint).
- “Action-first modes” now *do not* RAG-fallback empty successful READ action payloads (empty list is a valid user-visible result).
- Vector space selection is validated:
  - invalid `vectorSpace` values from the LLM are filtered and recorded in metadata
  - deterministic mode can fall back to a bounded fanout across configured vector spaces

### 1.3 Runtime product authz (remote API by default when dev defaults are off)

Runtime now ships a built-in remote `EntityAccessPolicy` implementation:
- Remote contract: `POST /api/authz/check` with canonical verified `authContext`, compatibility aliases, `requestedScopes`, and `requestContext`; response at minimum returns `{granted:boolean, reason?:string, policyVersion?:string}`
- Fail-closed: timeouts/unavailability/unparseable payload => deny

Important switch:
- `AI_FABRIC_RUNTIME_DEV_DEFAULTS_ENABLED=true` registers a dev **allow-all** policy hook and disables product authz wiring.
- `AI_FABRIC_RUNTIME_DEV_DEFAULTS_ENABLED=false` (default) uses product authz wiring (remote mode by default).

### 1.4 Ecommerce Store reset + “eventful clear”

Ecommerce Store keeps UI-friendly admin maintenance endpoints:
- `/api/admin/demo/reset`: bulk clears domain data and (optionally) clears runtime vectors (bulk).
- `/api/admin/demo/clear`: deletes indexed entities via service delete methods so delete-index events fire (eventful clear).
- Backwards compatible alias: `/api/admin/migration/clear` (same as reset).

---

## 2) Reference Topologies

### 2.1 Recommended (runtime + connector + store)

1. UI calls **runtime** for chat.
2. Runtime calls **rest connector** for actions and authz.
3. Rest connector calls **ecommerce store** for domain APIs (and optional authz upstream).
4. Ecommerce store pushes indexing to **runtime** (either directly or via rest connector runtime-proxy).

### 2.2 “Single base URL” demo (optional)

If `REST_CONNECTOR_RUNTIME_PROXY_ENABLED=true`, the rest connector can also proxy `/api/chat/*` to runtime so the UI can call only the connector base URL. This is a demo convenience, not the recommended production layout.

---

## 3) Local Quickstart (Docker Compose)

Use the 3-service compose file:

- `Real_Apps/ecommerce-store/deploy/docker/docker-compose.rest-connector.yml`

Starts:
- ecommerce-store on `http://localhost:8096`
- rest-connector on `http://localhost:8082`
- runtime on `http://localhost:8097`

Indexing notes:
- Indexing requires embeddings enabled in runtime (`OPENAI_ENABLED=true` + valid `OPENAI_API_KEY`) and a configured vector DB.

---

## 4) Railway Deployment Cheatsheet (3 Services)

### 4.1 Ecommerce Store (domain APIs)

Dockerfile:
- `Real_Apps/ecommerce-store/Dockerfile`

Common env vars:
- `APP_DEMO_SEED_DATA=true|false` (seed demo data; this is for ecommerce-store only)
- `CONNECTOR_INDEXING_ENABLED=true|false`
- `CONNECTOR_INDEXING_RUNTIME_BASE_URL=https://<rest-connector>.up.railway.app` (or runtime direct)
- `CONNECTOR_INDEXING_API_KEY=<value>` (only if the rest connector inbound auth is enabled)
- `CONNECTOR_INDEXING_API_KEY_HEADER=X-AIFABRIC-API-KEY`
- Admin auth for store reset endpoints is enabled by default:
  - Preferred: `APP_ADMIN_API_KEY=<value>`
  - Preferred: `APP_ADMIN_API_KEY_HEADER=X-ADMIN-API-KEY`
  - Compatibility aliases accepted by ecommerce-store: `CONNECTOR_ADMIN_API_KEY`, `CONNECTOR_ADMIN_API_KEY_HEADER`
  - Local-only opt-out: `APP_ADMIN_AUTH_ENABLED=false` or `CONNECTOR_ADMIN_AUTH_ENABLED=false`

### 4.2 Generic REST Connector pattern (actions router + optional proxies)

Dockerfile options:
- No generic REST connector Dockerfile is shipped in this repository today.
- Use `ai-infrastructure-module/ai-fabric-relay/Dockerfile` for the supported relay service, or provide a customer-owned connector image.

Minimum env vars (recommended):
- `CONNECTOR_API_KEY=<strong-secret>`
- `CONNECTOR_API_KEY_ENABLED=true`
- `CONNECTOR_API_KEY_HEADER=X-AIFABRIC-API-KEY`
- `UPSTREAM_BASE_URL=https://<ecommerce-store>.up.railway.app`

Optional: runtime proxy (alias endpoints):
- `REST_CONNECTOR_RUNTIME_PROXY_ENABLED=true`
- `REST_CONNECTOR_RUNTIME_PROXY_BASE_URL=https://<runtime>.up.railway.app`
- `REST_CONNECTOR_RUNTIME_PROXY_TIMEOUT_MS=60000` (recommended on Railway)

Optional: authz proxy:
- `AUTHZ_ENABLED=true`
- `AUTHZ_UPSTREAM_BASE_URL=https://<your-authz-service>.up.railway.app`
  - If blank, it falls back to `UPSTREAM_BASE_URL` (so ecommerce-store can be the authz upstream in demos).

Optional: CORS (browser UI calling connector directly):
- `CORS_ALLOWED_ORIGINS=https://your-ui.example.com`
- `CORS_ALLOWED_ORIGIN_PATTERNS=https://*.your-ui.example.com`
- If your UI uses cookies/session auth across origins: `CORS_ALLOW_CREDENTIALS=true`

### 4.3 AI Fabric Runtime

Dockerfile options:
- Base: `ai-infrastructure-module/ai-fabric-runtime/Dockerfile` (bring your own `/config`)
- Railway: `ai-infrastructure-module/ai-fabric-runtime/deploy/railway/Dockerfile` (bakes the ecommerce demo config into `/config/`)

Minimum env vars:
- `ACTIONS_CONNECTOR_BASE_URL=https://<rest-connector>.up.railway.app`
- `ACTIONS_CONNECTOR_API_KEY=<same as CONNECTOR_API_KEY>`
- `OPENAI_ENABLED=true|false`
- `OPENAI_API_KEY=<secret>`

Dev defaults vs product authz:
- For demos: `AI_FABRIC_RUNTIME_DEV_DEFAULTS_ENABLED=true` (allow-all policy hook; do not use in production)
- For product mode: keep it unset or set `false` (remote authz policy is used by default)

Optional: protect runtime admin endpoints:
- `APP_ADMIN_API_KEY=<secret>`
- `APP_ADMIN_API_KEY_HEADER=X-ADMIN-API-KEY`
Optional: browser cookies/session auth across origins:
- `CORS_ALLOW_CREDENTIALS=true`

---

## 5) UI-Friendly Verification Endpoints

### 5.1 Runtime

- Health: `GET /actuator/health`
- Actions loaded: `GET /api/admin/actions/overview`
- Indexing overview: `GET /api/admin/indexing/overview`
- Vector scan (paged): `GET /api/admin/indexing/vectors?entityType=product&offset=0&limit=50`
- Clear vectors (bulk wipe): `POST /api/admin/migration/clear?confirm=true`

Admin auth:
- Runtime and real-app admin endpoints are protected by default.
- If admin auth is enabled and `APP_ADMIN_API_KEY` is blank, admin requests are denied.
- Callers must provide `APP_ADMIN_API_KEY_HEADER` with `APP_ADMIN_API_KEY`.
- Use an explicit local-only opt-out such as `APP_ADMIN_AUTH_ENABLED=false` for no-key demos.

### 5.2 Generic REST Connector

- Health: `GET /actuator/health`
- Loaded routing overview: `GET /api/admin/overview`
- Loaded actions list: `GET /api/admin/actions/overview`
- Action route details: `GET /api/admin/actions/{actionId}`

If `REST_CONNECTOR_RUNTIME_PROXY_ENABLED=true`, the connector also exposes:
- Runtime chat proxy: `POST /api/chat/me/query`
- Runtime data-sync alias: `/api/ai/data-sync/*`
- Runtime indexing inspection: `/api/admin/indexing/*`
- Runtime migration clear: `POST /api/admin/migration/clear`

Admin auth:
- If `APP_ADMIN_API_KEY` is set, connector `/api/admin/*` endpoints should use `X-ADMIN-API-KEY`.
- If no admin key is configured, the connector falls back to its inbound connector API key settings.

### 5.3 Ecommerce Store

- Health: `GET /actuator/health`
- Domain APIs: `/api/products`, `/api/carts`, `/api/orders`, `/api/reviews`, `/api/policies`, etc.
- Reset (bulk): `POST /api/admin/demo/reset`
- Clear (eventful): `POST /api/admin/demo/clear`
- Back-compat reset alias: `POST /api/admin/migration/clear`
- Demo authz: `POST /api/authz/check`

---

## 6) Indexing Behavior (What “Works” Means)

Ecommerce store publishes indexing events on create/update/delete for indexed entities (see its indexing listeners/services).

Two common ways to route the indexing push:

1. **Direct to runtime (canonical):**
   - ecommerce-store -> runtime `/api/ai/data-sync/*`

2. **Via connector alias (demo convenience):**
   - ecommerce-store -> rest-connector `/api/ai/data-sync/*` -> runtime `/api/ai/data-sync/*`
   - Requires `REST_CONNECTOR_RUNTIME_PROXY_ENABLED=true` on the rest connector.

Verification signals:
- Runtime `/api/ai/data-sync/vector-spaces` returns your configured entity types.
- Runtime `/api/admin/indexing/overview` shows counts incrementing after domain writes.
- Runtime `/api/admin/indexing/vectors?...` shows your indexed entities.

---

## 7) Authorization vs Orchestration Policy (Two Different “Denials”)

You can see two different denial classes in responses:

### 7.1 `ACTIONS_DISABLED_BY_POLICY`

Message: `Actions are disabled by server policy for this request.`

Cause:
- The resolved orchestration mode has `actions-enabled: false`.
- Example: in the commerce curated pack, `navigator_deep` disables actions intentionally.

Fix:
- Use an action-enabled mode (e.g. `navigator`, `cart_assistant`, `executor`), or override `ai.orchestration.modes.<mode>.actions-enabled=true`.

### 7.2 `Access denied by policy`

Message: `Access denied by policy.`

Cause:
- `AccessControlStep` denied the request via `EntityAccessPolicy`.
- In product mode, this is typically the runtime remote authz call (`/api/authz/check`) failing or returning `granted=false`.

Fix:
- For demos: set `AI_FABRIC_RUNTIME_DEV_DEFAULTS_ENABLED=true` (allow-all; not for prod).
- For product mode: ensure the authz endpoint is reachable and `AUTHZ_ENABLED=true` on the rest connector proxy (if using the connector hop).

---

## 8) Common Troubleshooting

### 8.1 `URI is not absolute`

Cause:
- A base URL env var is missing a scheme.

Fix:
- Use `https://...` not `myhost.com`.
  - `ACTIONS_CONNECTOR_BASE_URL=https://...`
  - `CONNECTOR_INDEXING_RUNTIME_BASE_URL=https://...`

### 8.2 `Connector service unavailable`

Cause:
- Wrong connector base URL, connector is down, or runtime cannot reach it.

Fix:
- Check `GET {connector}/actuator/health`.
- Check `GET {connector}/api/admin/overview` with `X-ADMIN-API-KEY` when `APP_ADMIN_API_KEY` is configured.
- Otherwise use the configured connector inbound API key.

### 8.3 `quantity must be >= 1`

Cause:
- upstream API validation rejects `0`.

Fix:
- Prefer routing templates with defaults: `{{params.quantity|1}}`.
- The connector also clamps non-positive numeric template values to the default.

### 8.4 Retrieval uses the “wrong” vector space (or a space that doesn’t exist)

Behavior:
- Requested vector spaces are validated against configured entity types.
- Invalid spaces are filtered and recorded (see `vectorSpacesInvalidRequested` in metadata).
- Deterministic mode can fall back to a bounded fanout across configured spaces when no valid space is selected.

---

## 9) Related Docs (Deep Dives)

- Generic REST connector pattern guide:
  - `docs/Framework-Dev-Guides/connectors/GENERIC_REST_API_CONNECTOR_GUIDE.md`
- Relay deployment guide:
  - `docs/Framework-Dev-Guides/connectors/RELAY_IMPLEMENTATION_AND_DEPLOYMENT_GUIDE.md`
- Remote authz plan:
  - `changes/Productization/REMOTE_ACCESS_CONTROL_VIA_REST_CONNECTOR_PLAN.md`
- Verification checklist:
  - `changes/Productization/VERIFICATION_PLAYBOOK.md`
