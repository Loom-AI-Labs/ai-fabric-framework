# Runtime Authorization & Access Control (Product Deployments)

Status: draft (2026-03-25)

This document explains how **production runtime deployments** should implement authorization, and how to connect the runtime to a **customer-owned user/authorization API** or a **configuration-driven policy file**.

Related docs:
- `../retrieval-vectorization/DATA_SYNC_PUSH_API_GUIDE.md` (Data Sync uses the same access-control hook)
- `../actions-governance/ACTIONS_CONNECTOR_AND_RELAY_GUIDE.md` (trace and defense-in-depth patterns)
- `./PRIVATE_RUNTIME_CUSTOMER_INTEGRATION_GUIDE.md`
- `./PUBLIC_RUNTIME_BROWSER_TOKEN_INTEGRATION_GUIDE.md`
- Plan: `changes/Productization/REMOTE_ACCESS_CONTROL_VIA_REST_CONNECTOR_PLAN.md` (recommended product direction)

---

## 0) TL;DR

- AI Fabric Runtime does **not** ship your business authorization rules.
- Framework users can provide a Spring bean implementing `com.ai.infrastructure.access.policy.EntityAccessPolicy` (authorization decisions).
- Runtime product deployments can instead use the built-in **remote HTTP authz policy** (no custom Java code) via `ai.fabric.runtime.authz.*`.
- Optionally provide `com.ai.infrastructure.chat.spi.ChatSessionAccessControlPolicy` (conversation-level rules).
- Dev/test/demos: explicitly enable permissive defaults (`ai.fabric.runtime.dev-defaults.enabled=true` or `AI_FABRIC_RUNTIME_DEV_DEFAULTS_ENABLED=true`).
- Production: keep dev defaults disabled and provide a real `EntityAccessPolicy`.
- Default customer-facing posture should be private runtime:
  - browser -> customer backend -> private runtime -> private connector
- Public runtime browser-token mode is opt-in and should use short-lived signed tokens rather than raw browser identity fields.

---

## 1) Where Authorization Is Enforced

The runtime uses a single authorization hook (`EntityAccessPolicy`) via `AIAccessControlService`.

### 1.1 Chat / Orchestration Gate (runtime requests)

- Enforced by: `AccessControlStep` -> `AIAccessControlService` -> `EntityAccessPolicy`

Typical access request:
- `resourceId`: `rag:intent`
- `operationType`: `READ`
- `userId`: compatibility alias only
- canonical actor context should now come from verified runtime auth context
- `sessionId`: anonymous or effective session id derived from verified auth context when applicable

### 1.2 Data Sync Ingestion Gate (indexing/upserts/deletes)

- Enforced by: `DataSyncService` -> `AIAccessControlService` -> `EntityAccessPolicy`

Typical access request:
- `resourceId`: `vectorSpace:<entityType>` (example: `vectorSpace:product`)
- `operationType`: `WRITE` for upserts, `DELETE` for deletes
- `trace.authContext.subjectId`: required by the push API and becomes the canonical actor id for authorization
- `trace.authContext`: passed through to `AIAccessControlService` as the verified subject context

### 1.3 Runtime Admin Endpoints

- `/api/admin/*` endpoints are protected separately via `app.admin.api-key`.
- In production: configure `APP_ADMIN_API_KEY` and send `X-ADMIN-API-KEY: <value>` (or override `app.admin.api-key-header`).

---

## 2) Who Should Provide `EntityAccessPolicy`

### 2.1 Framework usage (embedded)

Concretely, this means:
- Your host Spring application should include a Spring `@Bean` (or `@Component`) implementing `EntityAccessPolicy`.
- Demo deployments often enable dev defaults for convenience; production should not.

### 2.2 Runtime product deployments (recommended)

You can avoid shipping custom Java code by configuring runtime to call a **customer-owned authorization API**:
- Runtime calls `POST /api/authz/check`
- Default wiring is via the Generic REST Connector (Runtime -> REST connector -> customer authz service)

This is configured via `ai.fabric.runtime.authz.*` (see Option B below).

---

## 3) What The Policy Receives (Inputs You Can Authorize On)

The hook signature is:

```java
boolean canAccess(AIAccessSubjectContext authContext, Map<String, Object> entity);
```

The `entity` map is built by `AIAccessControlService` and typically contains:
- `resourceId` (String)
- `operationType` (String)
- `timestamp` (LocalDateTime)
- `context` (String, optional)
- `purpose` (String, optional)
- `metadata` (Map, optional)
- `userAttributes` (Map, optional)

`metadata` and `userAttributes` are normalized before policy evaluation: null keys and values are
dropped, strings are trimmed, blank strings are removed, nested maps/lists are recursively
normalized, and unsupported object values are converted to strings. This keeps access decisions
stable and prevents malformed optional context from bypassing the fail-closed policy path.

Examples:

Chat gate example:
- `resourceId=rag:intent`
- `operationType=READ`
- `metadata.entryPoint=RAG_ORCHESTRATOR`

Data Sync example:
- `resourceId=vectorSpace:product`
- `operationType=WRITE`
- `metadata.vectorSpace=product`
- `metadata.entityId=SKU-123`
- `metadata.authContext.subjectId=system_shopify_sync`
- `metadata.tenantId=<from trace.metadata.tenantId if supplied>`

---

## 4) Option A: Authorization From A Config File (Allowlist/Rules)

Use this when:
- you want a simple "system user" allowlist (sync jobs, backfills), or
- you want a minimal first production cut (no external dependency).

### 4.1 Example policy file (YAML)

Add a file to your runtime deployment (mounted or baked) such as:

```yaml
app:
  authz:
    rules:
      - subjectId: "system_shopify_sync"
        resources: ["vectorSpace:*"]
        operations: ["WRITE", "DELETE"]
      - subjectId: "support_agent"
        resources: ["rag:intent"]
        operations: ["READ"]
```

### 4.2 Implementation sketch

Implement `EntityAccessPolicy` and load config using `@ConfigurationProperties`:

```java
@Component
@RequiredArgsConstructor
public class ConfigFileEntityAccessPolicy implements EntityAccessPolicy {
  private final AuthzRulesProperties rules;

  @Override
  public boolean canAccess(AIAccessSubjectContext authContext, Map<String, Object> entity) {
    String subjectId = authContext != null ? authContext.getSubjectId() : null;
    String resourceId = Objects.toString(entity.get("resourceId"), "");
    String op = Objects.toString(entity.get("operationType"), "");
    return rules.allows(subjectId, resourceId, op);
  }
}
```

Recommended behavior:
- Deny by default.
- Treat missing/blank `authContext.subjectId` as denied.
- Keep rules small and auditable.

### 4.3 Security Analyzer Audit Events

`AISecurityService` also requires a verified `authContext.subjectId` or `authContext.sessionId`.
Requests without either identity fail closed with a concise rejection message. Security audit event
ids use the form `SEC_<epochSecond>_<serviceSequence>`, so multiple requests handled in the same
second remain distinguishable in local event history.

---

## 5) Option B: Call A Customer Authorization API (Recommended For Product)

Use this when:
- you already have a user/role/tenant service, or
- you need fine-grained and centrally managed authorization.

### 5.1 Suggested HTTP contract

Runtime (policy hook) calls your internal service:

- `POST /api/authz/check`

Request:

```json
{
  "contractVersion": "AUTH_CONTEXT_V1",
  "userId": "u_123",
  "subjectId": "u_123",
  "subjectType": "END_USER",
  "authMode": "PRIVATE_RUNTIME_BACKEND_MEDIATED",
  "sessionId": "sess_456",
  "deploymentId": "dep_123",
  "customerId": "cus_123",
  "tenantId": "ten_123",
  "grantedScopes": ["chat:query", "chat:conversations"],
  "requestedScopes": ["chat:query"],
  "resourceId": "vectorSpace:review",
  "operationType": "WRITE",
  "requestContext": {
    "context": "customer asks to update shipping details",
    "purpose": "chat-orchestration",
    "entryPoint": "RAG_ORCHESTRATOR"
  },
  "metadata": { "tenantId": "t_1", "entityId": "801" },
  "compatibilityAliases": {
    "userId": "u_123",
    "sessionId": "sess_456"
  },
  "authContext": {
    "subjectId": "u_123",
    "subjectType": "END_USER",
    "authMode": "PRIVATE_RUNTIME_BACKEND_MEDIATED",
    "sessionId": "sess_456",
    "deploymentId": "dep_123",
    "customerId": "cus_123",
    "tenantId": "ten_123",
    "grantedScopes": ["chat:query", "chat:conversations"]
  }
}
```

Response:

```json
{
  "granted": true,
  "reason": "OK",
  "reasonCode": "ALLOW",
  "requiresConfirmation": false,
  "requiresApproval": false
}
```

Recommended authz-service behavior:
- treat `authContext` as the canonical verified identity contract
- treat `compatibilityAliases.userId` and `compatibilityAliases.sessionId` as compatibility-only inputs
- use `requestedScopes` to distinguish endpoint-level intent from the broader `grantedScopes` on the caller token
- use `requestContext` for policy explanation and audit, not as a replacement for verified identity

### 5.2 Runtime product: built-in remote HTTP policy (no custom code)

Runtime ships a built-in remote HTTP policy (`REMOTE_HTTP`) which is **fail-closed**:
- Denies on timeout, 5xx/non-2xx, or unparseable payload.
- Uses `ai.actions.connector.base-url` + `ai.actions.connector.api-key.*` by default (so the same REST connector used for actions can proxy authz).

Example config:

```yaml
ai:
  fabric:
    runtime:
      authz:
        mode: REMOTE_HTTP
        remote:
          # Optional: defaults to ai.actions.connector.base-url when blank
          base-url: ${AUTHZ_BASE_URL:${ACTIONS_CONNECTOR_BASE_URL:}}
          path: /api/authz/check
          timeout-ms: 1500
          outbound-auth:
            type: INHERIT_ACTIONS_API_KEY
```

### 5.3 Framework: implement a custom bean (still supported)

If you are embedding the framework into your own Spring app, you can still implement `EntityAccessPolicy` as a bean and call your authz service however you want.

---

## 6) Multi-Tenant Considerations

### 6.1 Data Sync

For ingestion, prefer passing tenant context explicitly:
- Set `trace.authContext.tenantId` and, when useful for audit, `trace.metadata.tenantId` in the push request.
- Enforce tenant boundaries in `EntityAccessPolicy` using the verified auth context plus `entity.metadata.tenantId`.

### 6.2 Chat

For chat requests, do not trust a client-supplied `userId` field as your sole auth mechanism.

Recommended production pattern:
- private runtime default:
  - authenticate the user at your backend
  - call runtime as a trusted backend
  - send verified auth context to runtime
- public runtime opt-in:
  - use short-lived signed bearer tokens
  - anonymous mode still uses a runtime-issued anonymous token

In both modes:
- connector should remain private
- runtime should be the supported external integration surface
- `EntityAccessPolicy` should deny cross-tenant access based on verified auth context, not raw request identity

---

## 7) Deployment Modes (What To Configure)

### 7.1 Demo / Test

Goal: keep the runtime working without needing a custom authz service yet.

Runtime env vars:

```bash
AI_FABRIC_RUNTIME_DEV_DEFAULTS_ENABLED=true

# Protect /api/admin/* endpoints (indexing overview, action catalog overview, clear-vectors)
APP_ADMIN_API_KEY="dev-admin-key"
APP_ADMIN_API_KEY_HEADER="X-ADMIN-API-KEY"

# Optional local-only escape hatch for demos/tests that must run without an admin key.
# Do not use this in shared, hosted, or production environments.
# APP_ADMIN_AUTH_ENABLED=false
```

Notes:
- With dev defaults enabled, runtime registers an allow-all `EntityAccessPolicy` if you did not provide one.
- Runtime and real-app admin endpoints are protected by default.
- If admin auth is enabled and `APP_ADMIN_API_KEY` is missing, admin requests are denied.
- Disable admin auth only with an explicit local/demo opt-out such as `APP_ADMIN_AUTH_ENABLED=false`.

### 7.2 Production

Goal: explicit authorization with least-privilege and defense in depth.

Runtime env vars:

```bash
AI_FABRIC_RUNTIME_DEV_DEFAULTS_ENABLED=false

# Protect /api/admin/* endpoints
APP_ADMIN_API_KEY="<strong-secret>"
APP_ADMIN_API_KEY_HEADER="X-ADMIN-API-KEY"
```

Production checklist:
- Provide a real `EntityAccessPolicy` (config-file rules or remote authz API call).
- Enforce authentication at the edge (JWT/session) and do not trust client-supplied `userId` without verification.
- Restrict `/api/admin/*` at the network layer if possible (in addition to API key auth).
- Consider fail-closed behavior for misconfiguration: ensure missing/broken authz hooks do not produce 500s (return a deterministic deny instead via exception handling).

---

## 8) Common Failure Modes

- Everything becomes "Access denied by policy": no `EntityAccessPolicy` present and dev defaults disabled. Fix: ship a real policy or enable dev defaults in non-prod only.
- Data Sync returns `ACCESS_DENIED`: `trace.authContext.subjectId` missing, access evaluation failed closed, or policy denies `vectorSpace:<space>` writes. Fix: provide verified `trace.authContext` and grant WRITE/DELETE where appropriate.
