# AI Fabric Relay — Implementation & Deployment Guide (V1)

The **AI Fabric Relay** is an optional customer-side component that implements the **Customer Connector API** inside the customer environment (VPC/on‑prem), so internal systems do not need to be exposed publicly.

Reference:
- Architecture + contracts: `../actions-governance/ACTIONS_CONNECTOR_AND_RELAY_GUIDE.md`
- OpenAPI spec: `changes/Productization/customer-connector-api.openapi.yml`
- Implementation plan: `changes/Productization/RELAY_SERVICE_IMPLEMENTATION_PLAN.md`

---

## Status (as of 2026-02-13)

- **Implemented in code (V1):** `ai-infrastructure-module/ai-infrastructure-relay` (runnable Spring Boot service).
- The AI Fabric runtime already supports calling a connector endpoint (so the Relay can be introduced without changing orchestration semantics).
- Redis-backed nonce/idempotency/rate-limit stores are implemented and configurable (default remains in-memory for dev/single-instance).
- Packaging examples shipped: Dockerfile + Compose quickstart + Helm example chart.
- OpenAPI contract conformance tests shipped in the Relay module test suite (pinned to `changes/Productization/customer-connector-api.openapi.yml`).
- Still planned for production hardening: optional mTLS and additional operational controls.

---

## Reference implementation (V1)

Source:
- `ai-infrastructure-module/ai-infrastructure-relay`

Local run:
- `mvn -f ai-infrastructure-module/pom.xml -pl ai-infrastructure-relay spring-boot:run`

Core configuration keys:
- `relay.auth.apiKey.*` and/or `relay.auth.hmac.*`
- (explicit dev-only escape hatch) `relay.auth.allowUnauthenticated=true`
- `relay.store.backend=IN_MEMORY|REDIS` (default: `IN_MEMORY`)
- `relay.routing.mode=MAPPING|DISPATCHER`
- `relay.routing.actions.{actionId}.url` (mapping mode)
- `relay.routing.dispatcher.url` (dispatcher mode)
- `relay.routing.retrieval.url` (optional)

Runtime rule:
- When `relay.auth.allowUnauthenticated=false` (default), the Relay will **fail startup** unless inbound auth is configured.

---

## 1) What the Relay does

The Relay is responsible for:
- authenticating AI Fabric requests (recommended: HMAC)
- enforcing rate limits (defense in depth)
- audit logging (PII-safe)
- SSRF-safe routing (`actionId → internal endpoint` or single dispatcher)
- forwarding user/trace context for authorization and tracing
- returning a valid `ActionResult` to AI Fabric

The Relay is not responsible for:
- defining action contracts (params, required fields, confirmation text)
- LLM prompting or orchestration logic

---

## 2) Security model (must-have)

### 2.1 Authentication chain (defense in depth)

```
User → AI Fabric (authenticates user/session)
     → Relay (verifies AI Fabric signature)
     → Internal service (re-authorizes user)
```

Rule:
- internal services MUST re-authorize using forwarded user identity/claims.

### 2.2 Inbound request verification (HMAC recommended)

Recommended headers:
- `X-AIFABRIC-TIMESTAMP` (epoch seconds)
- `X-AIFABRIC-NONCE` (unique per request)
- `X-AIFABRIC-SIGNATURE` (base64 HMAC-SHA256 over `timestamp + "\n" + nonce + "\n" + body`)

Relay must verify:
- signature correctness
- timestamp is within `maxClockSkewSeconds` (replay protection)
- nonce uniqueness within TTL (optional but recommended)

### 2.3 Rate limiting (required)

Enforce:
- per-user limits
- per-action limits

On limit, return:
- `success=false`, `errorCode=RATE_LIMITED`
- optional `data.retryAfterSeconds`

### 2.4 Audit logging (required; PII-safe)

Log fields (minimum):
- timestamp
- `actionId`
- `trace.requestId`
- stable `trace.userId` (avoid email/phone/address)
- outcome: success/failure + `errorCode`
- latency

Do not log:
- full params bodies
- sensitive values (addresses, emails, tokens, payment info)

### 2.5 Network security

AI Fabric → Relay:
- TLS required
- HMAC verification required when exposed over the internet
- optional: mTLS later

Relay → internal services:
- customer choice, but TLS + service auth is recommended

---

## 3) Routing patterns (SSRF-safe)

### Pattern A (recommended): explicit mapping

Relay config maps `actionId` to internal endpoint:
- prevents SSRF (no URLs from AI Fabric)
- explicit allowlist (only declared actionIds can be routed)

### Pattern B: single dispatcher

Relay forwards to one internal endpoint:
- the internal dispatcher routes by `actionId`
- still SSRF-safe (single allowlisted URL)

Avoid:
- accepting arbitrary URLs from AI Fabric

---

## 4) Configuration schema (example)

```yaml
relay:
  auth:
    # Choose one (or both):
    apiKey:
      enabled: true
      header: X-AIFABRIC-API-KEY
      value: ${RELAY_API_KEY}
    # hmac:
    #   enabled: true
    #   secret: ${RELAY_HMAC_SECRET}
    #   maxClockSkewSeconds: 300
    #   nonceTtlSeconds: 600

  # In-memory by default (dev/single instance). Use REDIS for HA deployments.
  store:
    backend: IN_MEMORY
    # keyPrefix: aifabric:relay:
    # redis:
    #   uri: ${RELAY_REDIS_URI} # redis://:pass@host:6379/0 or rediss://...

  rateLimits:
    perUser:
      windowSeconds: 60
      maxRequests: 100
    perAction:
      create_purchase_order:
        windowSeconds: 60
        maxRequests: 10

  idempotency:
    enabled: true
    ttlSeconds: 172800 # 48h

  routing:
    mode: MAPPING
    actions:
      create_purchase_order:
        url: http://internal-api:8080/orders
        method: POST
        timeoutMs: 5000
      cancel_purchase_order:
        url: http://internal-api:8080/orders/cancel
        method: POST
        timeoutMs: 3000
    retrieval:
      url: http://internal-api:8080/retrieval/search
      timeoutMs: 5000
```

---

## 5) Deployment options

### 5.1 Docker (single instance)

- Run the relay as a container in the customer VPC.
- Expose only the relay port; keep internal services private.

Build (from repo root):
- `docker build -f ai-infrastructure-module/ai-infrastructure-relay/Dockerfile -t ai-fabric-relay:dev .`

### 5.2 Docker Compose (dev / quickstart)

Quickstart (from `ai-infrastructure-module/ai-infrastructure-relay`):
- `docker compose up --build`

What the Compose quickstart includes:
- `relay` (apiKey auth enabled, Redis store enabled, dispatcher routing)
- `redis` (for HA-safe stores)
- `internal-dispatcher` (WireMock stub for local end-to-end testing)

Try it (api key is `dev-relay-key` in the quickstart):

```bash
curl -sS http://localhost:8081/actions/execute \
  -H 'Content-Type: application/json' \
  -H 'X-AIFABRIC-API-KEY: dev-relay-key' \
  -d '{"actionId":"ping","params":{},"idempotencyKey":"act_1","trace":{"requestId":"req_1","conversationId":"c1","userId":"u1","sessionId":"s1"}}'
```

### 5.3 Kubernetes (production)

Recommended:
- Deployment + Service
- Ingress with TLS
- ConfigMap/Secret for relay config + HMAC secret
- Horizontal Pod Autoscaler (optional)

Helm example chart:
- `ai-infrastructure-module/ai-infrastructure-relay/deploy/helm/ai-fabric-relay/Chart.yaml`

---

## 6) Acceptance tests (must pass)

- Reject invalid/missing signature (401/403)
- Reject requests outside clock skew window
- Rate limit by user + by action
- Audit log written for every request (no PII)
- SSRF-safe routing (no dynamic URLs)
- Correctly forwards `trace` and preserves requestId correlation
- Returns valid `ActionResult` for both success and failure

Contract tests reference:
- `ai-infrastructure-module/ai-infrastructure-relay/src/test/java/com/ai/infrastructure/relay/contract/RelayOpenApiContractTest.java`
