# Private Runtime Customer Integration Guide

Status: current branch guide (2026-04-07)

This guide explains the recommended production integration posture for customer deployments:

- browser -> customer backend
- customer backend -> private runtime
- runtime -> private connector

This is the default secure mode for customer storefronts, packaged integrations, and enterprise deployments.

Related guides:

- `RUNTIME_AUTHORIZATION_AND_ACCESS_CONTROL_GUIDE.md`
- `PUBLIC_RUNTIME_BROWSER_TOKEN_INTEGRATION_GUIDE.md`
- `../ui-clients/PUBLIC_API_CLIENT_USER_GUIDE.md`

---

## 1) Why this is the default

Use private runtime when you want:

- the browser outside the AI identity boundary
- service-to-service trust between your backend and runtime
- end-user identity derived from a backend-issued assertion instead of raw request fields
- the connector to remain fully private

Do not make the browser call the connector directly in this mode.

---

## 2) Request flow

1. The customer logs into your storefront or app.
2. Your backend verifies that user with your normal session or identity system.
3. Your backend calls the private runtime.
4. The runtime derives the effective subject from verified auth context.
5. The runtime calls customer-owned authorization for sensitive retrieval or actions.
6. The runtime calls the private connector when actions or upstream integrations are needed.

In this posture:

- the backend proves it is a trusted caller
- the backend separately proves which end user it is acting for
- the runtime does not trust request `userId` or `ownerId` as authoritative identity

---

## 3) Runtime caller authentication

Phase-1 trusted backend auth uses:

- `X-AIFABRIC-RUNTIME-API-KEY`

The trusted backend secret is deployment-scoped and should stay server-side.

Your backend should call runtime with:

- `X-AIFABRIC-RUNTIME-API-KEY: <trusted-backend-key>`
- verified auth-context headers such as:
  - `X-AIFABRIC-AUTH-SUBJECT-ID`
  - `X-AIFABRIC-AUTH-SUBJECT-TYPE`
  - `X-AIFABRIC-AUTH-MODE`
  - `X-AIFABRIC-AUTH-CALLER-TYPE`
  - `X-AIFABRIC-AUTH-SESSION-ID`
  - `X-AIFABRIC-AUTH-DEPLOYMENT-ID`
  - `X-AIFABRIC-AUTH-CUSTOMER-ID`
  - `X-AIFABRIC-AUTH-TENANT-ID`
  - `X-AIFABRIC-AUTH-ISSUER`
  - `X-AIFABRIC-AUTH-EXPIRES-AT`
  - `X-AIFABRIC-AUTH-SCOPES`

Recommended values:

- `X-AIFABRIC-AUTH-MODE`: `PRIVATE_RUNTIME_BACKEND_MEDIATED`
- `X-AIFABRIC-AUTH-CALLER-TYPE`: `TRUSTED_BACKEND`
- `X-AIFABRIC-AUTH-SUBJECT-TYPE`: usually `END_USER`

---

## 4) End-user identity

Your backend should issue or derive a short-lived end-user assertion and translate that into runtime auth-context headers.

Recommended fields:

- subject id
- subject type
- session id
- deployment id
- customer id
- optional tenant id
- issuer
- expiry
- granted scopes

The runtime should be treated as the consumer of verified end-user context, not the issuer.

---

## 5) Runtime API expectations

In this mode, the supported external surface is the runtime.

Use runtime for:

- chat query
- one-time query
- suggestions
- conversation reads and deletes
- runtime auth-context inspection for verified callers
- runtime-backed operational reads
- runtime-backed connector admin overview and health

Do not build customer integrations around direct connector admin APIs.

If you need operational reads, prefer runtime-backed routes such as:

- `/api/chat/me/auth-context`
- `/api/admin/overview`
- `/api/admin/connector/health`
- `/api/admin/connector/overview`
- `/api/admin/connector/actions/overview`

---

## 6) Authorization model

Runtime should consult a customer-owned authorization service for:

- sensitive retrieval
- action execution
- cross-tenant or customer-bound access

The runtime remote authz contract now carries canonical verified auth context explicitly.

Compatibility aliases such as raw `userId` or `sessionId` still exist only to help migration.

Do not design new authz integrations around those aliases.

---

## 7) Public provisioning API interpretation

When integrating through the platform public provisioning API:

- use `integration.preferredIntegrationMode`
- use `integration.backendMediatedRuntimeBaseUrl` or `integration.preferredChatBaseUrl`
- use `integration.preferredOperationalBaseUrl`
- use `integration.preferredAuthContextUrl` for verified caller smoke checks
- use `integration.trustedBackendAuthorizationHeader`
- respect `integration.hostBackedRuntimeRequired=true`
- expect `integration.verifiedAuthContextRequired=true` in the recommended private-runtime posture

Do not expect:

- `connectorBaseUrl`
- direct browser-safe connector access

---

## 8) Example backend call

```http
POST /api/chat/me/query HTTP/1.1
Host: runtime-dep-example.up.railway.app
X-AIFABRIC-RUNTIME-API-KEY: <trusted-backend-key>
X-AIFABRIC-AUTH-SUBJECT-ID: customer-123
X-AIFABRIC-AUTH-SUBJECT-TYPE: END_USER
X-AIFABRIC-AUTH-MODE: PRIVATE_RUNTIME_BACKEND_MEDIATED
X-AIFABRIC-AUTH-CALLER-TYPE: TRUSTED_BACKEND
X-AIFABRIC-AUTH-SESSION-ID: session-456
X-AIFABRIC-AUTH-DEPLOYMENT-ID: dep-abc123
X-AIFABRIC-AUTH-CUSTOMER-ID: cus-001
X-AIFABRIC-AUTH-ISSUER: shop-backend
X-AIFABRIC-AUTH-EXPIRES-AT: 2026-04-07T12:00:00Z
X-AIFABRIC-AUTH-SCOPES: chat:query,chat:suggestions,chat:conversations
Content-Type: application/json

{
  "query": "Where is my order?"
}
```

Use `POST /api/chat/me/query` for chat panels that keep a stable `conversationId` and may show history.

Use `POST /api/chat/me/query-once` for page helpers, inline explanations, smoke checks, and other one-time answers that must not persist conversation turns. The request and response shape is the same as `/api/chat/me/query`, and it uses the same `chat:query` scope. Runtime treats `conversationId` as correlation only on this endpoint: it does not load persisted chat memory and does not record a chat turn. Do not send a `persistConversation` request flag; choose the endpoint by UX intent.

Recommended behavior:

- do not send request `userId`
- do not send request `ownerId`
- prefer the `/api/chat/me/*` surface for verified callers
- use runtime `authContext` in the response as the source of truth for the effective actor

For smoke verification or integration debugging, you can also call:

```http
GET /api/chat/me/auth-context HTTP/1.1
Host: runtime-dep-example.up.railway.app
X-AIFABRIC-RUNTIME-API-KEY: <trusted-backend-key>
X-AIFABRIC-AUTH-SUBJECT-ID: customer-123
X-AIFABRIC-AUTH-SUBJECT-TYPE: END_USER
X-AIFABRIC-AUTH-MODE: PRIVATE_RUNTIME_BACKEND_MEDIATED
X-AIFABRIC-AUTH-CALLER-TYPE: TRUSTED_BACKEND
X-AIFABRIC-AUTH-SESSION-ID: session-456
X-AIFABRIC-AUTH-DEPLOYMENT-ID: dep-abc123
X-AIFABRIC-AUTH-CUSTOMER-ID: cus-001
X-AIFABRIC-AUTH-ISSUER: shop-backend
X-AIFABRIC-AUTH-EXPIRES-AT: 2026-04-07T12:00:00Z
X-AIFABRIC-AUTH-SCOPES: chat:query,chat:suggestions,chat:conversations
```

The response should show the canonical verified subject, auth mode, caller type, session id, granted scopes, and any compatibility warnings.

---

## 9) Verification checklist

Must prove:

- trusted backend header is required
- invalid backend caller is denied
- runtime rejects missing or conflicting verified auth context when strict mode is enabled
- conversation ownership resolves from verified subject identity
- runtime-backed connector health and overview work without direct connector access
- customer authz decisions apply to retrieval and action-time authorization

---

## 10) Recommended adoption order

1. Provision the deployment.
2. Configure trusted-backend auth.
3. Configure runtime auth mode to strict verified-context posture.
4. Integrate your backend with runtime.
5. Verify runtime-backed operational reads.
6. Only after that, connect browser UI or widget through your backend route.
