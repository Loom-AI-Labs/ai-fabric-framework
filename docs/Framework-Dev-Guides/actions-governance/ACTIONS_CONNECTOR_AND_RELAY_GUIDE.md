# Actions Execution (Local + Connector + Relay) — Architecture & Developer Guide

This document extends the V5 actions model described in:
- `./ACTIONS_AND_CONFIRMATION_INTERCEPTORS_GUIDE.md`

It adds a **language-agnostic** execution path for actions via a **Customer Connector API** (optionally implemented using an **AI Fabric Relay**).
It also documents a planned **Generic REST API Connector** pattern (actionId → upstream REST endpoint routing).

> This is an architecture + productization guide for the **Actions** solution only.
> It does **not** define commerce entities, domain logic, or “built-in” domain actions.

---

## Status (as of 2026-06-19)

### Implemented in code (opt-in modules + ai-fabric-core)

- `ai-fabric-actions-connector`:
  - File-based connector action catalog loading + validation (`ai.actions.sources[*]`)
  - Connector-backed execution (`ai.actions.connector.*`) via `POST /actions/execute`
  - Idempotency key generation for `WRITE_ONLY` / `READ_WRITE` (`act_{ulid}`)
  - Bounded retries derived from `errorCode` + idempotency safety
  - Optional API key header + optional HMAC signing headers (outbound)
  - Strict payload parsing (object vs list payload contracts)
- `ai-fabric-actions-registry`:
  - DB-backed action registration controller (`POST/DELETE/GET /api/ai/actions/registry`)
  - DB-backed connector action catalog contributor (loaded into the unified action registry)
  - Enabled by `ai.actions.db.enabled=true`
  - Liquibase changelog shipped at `ai-infrastructure-module/ai-fabric-actions-registry/src/main/resources/db/changelog/ai-actions-registry-changelog.yaml`
  - Optional “zero-config” Liquibase runner module: `ai-fabric-actions-registry-liquibase`
- `ai-fabric-retrieval-connector`:
  - Documents-only external retrieval via `POST /retrieval/search` (as a `RAGProvider`)
- `ai-fabric-relay` (runnable service):
  - Customer-side Relay implementation of the Customer Connector API (`/actions/execute`, `/retrieval/search`)
  - Inbound auth (API key and/or HMAC), replay protection, rate limiting, idempotency (in-memory by default; Redis backend supported), SSRF-safe routing (mapping/dispatcher)
  - OpenAPI contract conformance tests pinned to `changes/Productization/customer-connector-api.openapi.yml`
- `ai-fabric-core`:
  - Unified action registry (annotation + contributed sources)
  - Connector actions registered alongside `@AIAction` actions
  - Name collisions fail fast at startup (no silent overrides)

Opt-in:
- Add dependency `io.github.loom-ai-labs:ai-fabric-actions-connector` to enable connector-backed actions.
- Add dependency `io.github.loom-ai-labs:ai-fabric-retrieval-connector` to enable documents-only external retrieval.

### Implemented in docs (changes/Productization)

- OpenAPI spec: `changes/Productization/customer-connector-api.openapi.yml`
- Connector implementation guide: `../connectors/CUSTOMER_CONNECTOR_IMPLEMENTATION_GUIDE.md`
- Relay specification + deployment guide: `../connectors/RELAY_IMPLEMENTATION_AND_DEPLOYMENT_GUIDE.md`
- Relay implementation plan (V1 build steps): `changes/Productization/RELAY_SERVICE_IMPLEMENTATION_PLAN.md`
- Retrieval connector guide (documents-only): `../retrieval-vectorization/RETRIEVAL_CONNECTOR_GUIDE.md`
- Generic REST connector pattern guide: `../connectors/GENERIC_REST_API_CONNECTOR_GUIDE.md`

### Not implemented yet (planned)

- Relay hardening:
  - optional mTLS inbound auth (enterprise)
- Runnable `ai-fabric-generic-rest-connector` service module:
  - `actionId → upstream REST endpoint` routing
  - Docker/Railway packaging
  - production secret/tenant model

---

## 0) Goal (What we’re shipping)

AI Fabric provides:
- A **single action model** (metadata + safety semantics + confirmations + conversation flows)
- A **single orchestration UX contract** (missing params → clarification; confirmation → pending action; yes/no → correct execution)
- Pluggable **action execution backends**

Customers provide:
- Their **action catalog** (definitions / contracts)
- Their **action implementations** (HTTP endpoints in any language)

Curated packs / licensing provide:
- **Modes + prompt optimizations + routing defaults**
- They do **not** hardcode business logic and do **not** require domain-specific entities.

---

## 1) Concepts (What runs where)

### 1.1 Action definition vs action execution

**Action definition** is what the orchestrator and the LLM need to know:
- `name`, `description`, `category`
- `accessMode`: `READ | READ_WRITE | WRITE_ONLY`
- parameter contract (required fields + validation hints)
- `requiresConfirmation` and optional confirmation message template

`readActionResolutionEligible` is valid only for `READ` actions. `READ_WRITE` is side-effecting and
must not be exposed as a planner-driven read helper; boot-time catalogs and DB registration reject
that combination. `READ_WRITE` actions are also not grounding-eligible by default.

**Action execution** is how the action actually runs:
- **Local (in-process)**: Java `@AIAction` handlers executed in the same JVM as AI Fabric
- **Connector (out-of-process)**: AI Fabric calls a customer HTTP API and receives an `ActionResult`

The orchestrator should not care *where* the action runs. It only cares that:
- the action is defined
- the action is allowed
- missing parameters are handled deterministically
- confirmation rules are respected
- an `ActionResult` is returned in the framework contract

### 1.2 Customer Connector API

The **Customer Connector API** is a small, stable HTTP contract implemented by the customer (or by your integrator team).

AI Fabric (hosted or self-hosted) calls **one** connector base URL, typically:
- `POST /actions/execute` (actions)
- (optional) `POST /retrieval/search` (documents-only retrieval for RAG)

### 1.3 Relay (customer-side agent)

A **Relay** is an official AI Fabric component that implements the Customer Connector API.

Customers deploy the Relay inside their environment (VPC/on‑prem). The Relay then:
- receives `actionId + params` from AI Fabric
- calls internal services safely (private network)
- returns an `ActionResult` back to AI Fabric

This avoids forcing customers to expose internal systems publicly.

---

## 2) Unifying actions (no conflict, one model)

There is no fundamental conflict between:
- annotation-based actions (`@AIAction`) and
- connector-executed actions (HTTP)

**Unify the contract and orchestration semantics.**
Keep execution as an implementation detail.

### 2.1 Canonical action model (single truth)

Regardless of backend, every action should have a canonical model that includes:
- name + description + category
- access mode (`READ`, `READ_WRITE`, `WRITE_ONLY`)
- parameter schema (required + validation)
- requiresConfirmation + confirmation message strategy

### 2.2 Execution backends (two implementations)

**A) Local backend**
- Source of truth: Java `@AIAction` + `@Param` metadata (see V5 guide)
- Execution: in-process via `AIActionRegistry` + `AnnotatedAIActionHandler`

**B) Connector backend**
- Source of truth: config-defined action contract (file) and/or DB-backed registry (both validated)
- Execution: HTTP call to the Customer Connector API

### 2.3 Collision handling (fail fast)

If an action name is registered twice (local + connector, or connector + connector):
- **Fail fast** at registration/startup.

No silent overrides.

---

## 3) Action catalog (how actions are defined)

### 3.1 Local actions (Java annotations)

Use the V5 guide:
- `@AIAction` + `@ActionExecute` (+ optional `@ActionAllowed`, `@ActionConfirmation`, `@ActionFacts`)
- Typed `ActionResult.data` via `ActionResultContracts.object(...)` or `ActionResultContracts.list(...)`

### 3.2 Connector actions (file-based contract — first release)

For hosted + language-agnostic adoption, start with a file-based action contract loaded at boot.

Implementation note:
- Connector catalogs + execution live in the optional module `ai-fabric-actions-connector`.

**Recommended file:** `ai-actions.yml`

Example:

```yaml
ai:
  actions:
    sources:
      - type: file
        path: classpath:ai-actions.yml

actions:
  - name: create_purchase_order
    description: "Create a purchase order for a product"
    category: "commerce" # examples only; framework is domain-agnostic
    accessMode: WRITE_ONLY
    requiresConfirmation: true
    confirmationMessage: "Create purchase order for {{quantity}} × {{sku}}?"
    params:
      - name: sku
        type: string
        description: "Product SKU"
        required: true
        pattern: "^[A-Z0-9_-]{3,64}$"
      - name: quantity
        type: integer
        description: "Quantity"
        required: true
        min: 1
        max: 100
      - name: shippingAddress
        type: string
        description: "Shipping address"
        required: true
        sensitive: true
      - name: email
        type: string
        description: "Customer email"
        required: true
        pattern: "^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$"
        sensitive: true

  - name: cancel_purchase_order
    description: "Cancel an existing purchase order"
    category: "commerce"
    accessMode: WRITE_ONLY
    requiresConfirmation: true
    confirmationMessage: "Cancel order {{orderRef}}?"
    params:
      - name: orderRef
        type: string
        description: "Order reference"
        required: true
```

Notes:
- This schema mirrors V5’s `@Param` capabilities: `required`, `pattern`, `allowedValues`, `min`, `max`.
- `type` is for documentation + UI hints; runtime should validate using the same rules as the Java binder.
- `sensitive: true` means “do not log / do not echo in confirmation by default”.
- The framework remains domain-agnostic: commerce names are illustrative only.

### 3.3 DB-backed registration (V1 optional)

Implemented (opt-in module): `ai-fabric-actions-registry`
- Enable: `ai.actions.db.enabled=true`
- Security (recommended): `ai.actions.db.api-key.enabled=true` + `ai.actions.db.api-key.value=...`
- Liquibase (optional, “zero-config”):
  - Add module: `ai-fabric-actions-registry-liquibase`
  - Default: `ai.actions.db.liquibase.enabled=true`
  - Override changelog: `ai.actions.db.liquibase.change-log=classpath:db/changelog/ai-actions-registry-changelog.yaml`
  - If your app already uses Liquibase (`spring.liquibase.*` or a `db.changelog-master.*`), include the changelog in your master instead of relying on defaults.
  - The helper respects explicit `spring.liquibase.change-log`, explicit `spring.liquibase.enabled=false`, and Boot default master changelogs. If only `spring.liquibase.enabled=true` is set, it still supplies the registry changelog when `ai.actions.db.enabled=true`.
- Endpoints:
  - `GET /api/ai/actions/registry`
  - `POST /api/ai/actions/registry`
  - `DELETE /api/ai/actions/registry/{actionName}`
- Behavior:
  - persists action definitions in DB
  - fails fast on duplicates/collisions
  - refreshes the unified action registry so changes are available without restart
  - validates fixed DB field limits, regex patterns, duplicate allowed values, and flat param shape before persistence
  - revalidates stored DB definitions when loading handlers; stale/manual invalid rows are skipped and
    logged instead of being exposed as broken runtime actions

DB-backed registration is intentionally narrower than the file-based catalog:
- supports flat parameter definitions only
- does not persist custom `displayName`, built-in shell/card bindings, provenance, `postPolicies`, `llmFacts`, nested schemas, assistant-resolution metadata, or MCP/runtime adapter config
- rejects overlong values before database writes:
  - action/param name: 128 characters
  - category: 128 characters
  - action/param description and confirmation message: 1024 characters
  - param regex pattern and default value: 512 characters
  - allowed value entry: 256 characters

The runtime treats file + DB sources uniformly (same canonical model).

---

### 3.4 Action registration lifecycle (startup validation + template safety)

The connector path only works if the action catalog is **validated once** and then treated as stable runtime contract.
Prefer failing at startup (or at registration time) over discovering problems in production.

#### 3.4.1 Discovery / loading order

Recommended load order (no precedence; duplicates are fatal):
1. Local annotations (`@AIAction`)
2. File-based actions (`ai-actions.yml`)
3. DB-backed actions (when enabled)

**Collision detection**:
- Build one unified registry from all sources.
- If any action `name` appears more than once: **fail fast** (startup failure for boot sources; registration failure for DB API).

#### 3.4.2 Schema validation (boot-time / registration-time)

Validate before exposing actions to the LLM:
- `name`: non-empty, trimmed, stable identifier (recommended: `snake_case`; enforcement should be **optional/configurable**)
- `accessMode`: must be one of `READ | READ_WRITE | WRITE_ONLY`
- `readActionResolutionEligible`: allowed only when `accessMode=READ`
- `params`: validate `required`, `pattern`, `allowedValues`, `min`, `max` using the same rules as the Java binder
- `requiresConfirmation`: boolean
- `confirmationMessage` (if present): validate template placeholders

#### 3.4.3 Confirmation template rendering (escape-by-default)

If connector actions declare a `confirmationMessage` template (example: `Create purchase order for {{quantity}} × {{sku}}?`):
- Rendering happens in **AI Fabric** (not in the connector), using the validated params for that action.
- Placeholders must match declared `params[].name`.
- Templates must be **escape-by-default** (UI-safe, no HTML/JS injection).
- `sensitive: true` params must be **redacted by default** in confirmations (and in logs), unless an app explicitly opts in to showing them.

Rule of thumb:
- Good: `Cancel order {{orderRef}}?`
- Avoid: `Ship to {{shippingAddress}}?` (shipping address is typically sensitive)

#### 3.4.4 Runtime expectations

- File-based contracts are immutable at runtime (restart required to change).
- DB-backed actions can be added/removed via API, but must pass the same validations and must still fail fast on duplicates.

---

## 4) Customer Connector API (execution contract)

AI Fabric should call a **single connector base URL**.
Do not let the model provide arbitrary URLs.

### 4.1 `POST /actions/execute`

#### Request (example)

```json
{
  "actionId": "create_purchase_order",
  "params": { "sku": "SKU-123", "quantity": 1, "email": "a@b.com", "shippingAddress": "..." },
  "idempotencyKey": "act_01H...",
  "trace": {
    "requestId": "req_...",
    "conversationId": "chat-...",
    "userId": "user-...",
    "sessionId": "sess-..."
  }
}
```

Notes:
- `actionId` must refer to an action that is already in the validated catalog (no dynamic URLs, no ad-hoc actions).
- `trace` is required for auditability and downstream authorization (see Relay security model).

#### Response (success)

Response must match the framework action result contract (`ActionResult`):

```json
{
  "success": true,
  "message": "Purchase order created",
  "data": {
    "orderRef": "PO-123",
    "total": 249,
    "currency": "USD"
  }
}
```

#### Response (error)

For expected business/application failures, return `success=false` with a stable `errorCode`.
The `message` must be user-safe (no secrets, no internal stack traces).

```json
{
  "success": false,
  "errorCode": "INSUFFICIENT_INVENTORY",
  "message": "This item is out of stock.",
  "data": {
    "sku": "SKU-123",
    "availableQuantity": 0
  }
}
```

#### HTTP status code guidance

To keep orchestration deterministic:
- Prefer `HTTP 200` with an `ActionResult` body for **handled** outcomes (success or failure).
- Use non-2xx responses only for **protocol-level** failures (auth failed, invalid JSON, missing required top-level fields).
  - If possible, still return an `ActionResult` body even when using non-2xx.
- AI Fabric treats the HTTP status as authoritative: any non-2xx connector response is a failure even if the body says `success=true`.
- For non-2xx responses with a valid `success=false` body, AI Fabric preserves the connector `errorCode`, user-safe `message`, and compatible `data` payload.
- For non-2xx responses with an empty, invalid, or misleading body, AI Fabric maps the status deterministically:
  - `408` -> `TIMEOUT`
  - `429` -> `RATE_LIMITED`
  - `5xx` / missing status -> `SERVICE_UNAVAILABLE`
  - other `4xx` -> `ACTION_EXECUTION_FAILED`
- For `HTTP 200` responses with `success=true`, AI Fabric validates the `data` payload against the
  action result contract. Malformed successful payloads fail closed with `errorCode=INVALID_RESPONSE`.
- For handled connector failures (`success=false`), AI Fabric preserves the connector `errorCode` and
  message; malformed optional failure `data` is ignored rather than masking the business failure.

#### Standard error codes (recommended)

Connector implementations should use a small, stable set of error codes so AI Fabric can handle them consistently.

| `errorCode` | Meaning | Retriable by default? | Typical UX behavior |
|---|---|---:|---|
| `INVALID_PARAMETER` | Input failed validation | No | Ask user to correct input |
| `UNAUTHORIZED` | Missing/invalid auth | No | Stop + require configuration fix |
| `FORBIDDEN` | Auth ok but not allowed | No | Explain insufficient permissions |
| `NOT_FOUND` | Resource not found | No | Explain it doesn’t exist |
| `CONFLICT` | State conflict (already cancelled, etc.) | No | Explain current state |
| `BUSINESS_RULE_VIOLATION` | Domain-specific rule failed | No | Explain rule in message |
| `RATE_LIMITED` | Too many requests | Yes | Ask user to retry later |
| `TIMEOUT` | Upstream timed out | Yes | Retry (bounded) |
| `SERVICE_UNAVAILABLE` | Temporary outage | Yes | Retry (bounded) |
| `INVALID_RESPONSE` | Connector returned a malformed successful payload | No | Treat as connector contract bug |
| `IDEMPOTENCY_CONFLICT` | Same key, different params | No | Treat as integration bug |
| `ACTION_EXECUTION_FAILED` | Generic failure | Maybe | Retry only if safe for the action |

**Retry semantics note:**
- Today the framework action result contract carries `errorCode` but not a dedicated `retriable` boolean.
- Hosted AI Fabric should derive retryability from `errorCode` (and the action `accessMode` + idempotency support).

#### Payload rules (important)

Align with V5 action payload contracts:
- `data` **MUST** be one of:
  - **object payload**: arbitrary key/value object (must not use reserved list keys)
  - **list payload**: must use reserved keys `_items`, `_count` (optionally `_totalCount`, `_cursor`)
- Reserved list keys are defined by `ActionResultContracts`:
  - `_items`, `_count`, `_totalCount`, `_cursor`

List payload example:

```json
{
  "success": true,
  "message": "Products",
  "data": {
    "_count": 2,
    "_items": [
      { "id": "p1", "title": "..." },
      { "id": "p2", "title": "..." }
    ]
  }
}
```

##### Reserved keys validation (connector + framework)

If `data` contains `_items`:
- `_items` **MUST** be an array
- `_count` **MUST** be an integer
- `_count` **MUST** equal `len(_items)` (use `_totalCount` for overall pagination counts)
- `_totalCount` (optional) **MUST** be an integer
- `_cursor` (optional) **MUST** be a string

For object payloads:
- Do **not** use reserved list keys (`_items`, `_count`, `_totalCount`, `_cursor`) as custom fields.
- Avoid `_`-prefixed custom keys entirely; treat `_` as a reserved namespace.

### 4.2 Idempotency implementation + retries (hosted safety)

For `WRITE_ONLY` / `READ_WRITE` actions:
- AI Fabric must send an `idempotencyKey`
- Connector/Relay must implement idempotency (at least “exactly-once per key” best-effort)
- Retries are only safe when idempotency is supported

#### 4.2.1 Key generation (who + format)

- **AI Fabric generates** `idempotencyKey` (do not accept client-generated keys).
- Recommended format: `act_{ulid}` (ULID is sortable, URL-safe, and index-friendly).
  - Example: `act_01HQRS123456789ABCDEFGHJK`

#### 4.2.2 Connector storage requirements (what to remember)

Connector/Relay must store (at minimum):
- `idempotencyKey`
- `actionId`
- a stable fingerprint of `params` (or the serialized request)
- the resulting `ActionResult`
- an expiry timestamp (TTL)

**TTL**:
- Minimum: 24 hours
- Recommended: 48 hours

#### 4.2.3 Duplicate handling (must be deterministic)

On duplicate request with the same key:
- Return the **same** `ActionResult` as the first successful/failed execution.
- Do not execute the side-effect twice.
- Recommended header: `X-Idempotent: true` (optional, informational).

If the same key is seen with **different** params:
- Return `success=false` with `errorCode=IDEMPOTENCY_CONFLICT`.

#### 4.2.4 Storage implementation options

- **Redis (recommended for hosted/prod)**: simple TTL, fast, scalable
- **Database table**: ACID guarantees, requires cleanup
- **In-memory (dev-only)**: acceptable for local demos; not safe for distributed systems

#### 4.2.5 Retry behavior (framework guidance)

- `READ` actions may be retried (bounded), even without idempotency.
- `WRITE_ONLY` / `READ_WRITE` actions must only be retried when:
  1) an `idempotencyKey` was provided, and
  2) the connector implements idempotency correctly

Hosted AI Fabric should bound retries (example defaults):
- max attempts: 3
- exponential backoff: 1s → 2s → 4s
- do not retry non-retriable `errorCode`s (see table above)

For `READ` actions:
- Retries are generally acceptable (still bounded)

### 4.3 Authentication

Supported patterns (choose one per customer):
- Static API key header
- HMAC-signed requests (recommended): include timestamp + nonce to prevent replay
- mTLS (best for enterprise later; optional early)

### 4.4 Timeouts + deterministic failures

Connector execution must be bounded:
- short connect + read timeouts
- deterministic error codes in `ActionResult.errorCode` for client/UI handling

---

## 5) Relay (recommended default for hosted deployments)

### 5.1 Who implements it?

AI Fabric implements and ships the Relay as an official component (Docker image / binary).
Customers deploy it and configure their internal routes/auth.

### 5.2 What the Relay is responsible for (and what it is not)

The Relay implements the **Customer Connector API** boundary.

The Relay is responsible for:
- verifying inbound requests from AI Fabric (auth + integrity)
- enforcing rate limits (defense in depth)
- routing `actionId` safely to internal endpoints (SSRF-safe)
- forwarding trace/user context for audit + authorization
- producing a valid `ActionResult` response (success or error)

The Relay is **not** responsible for:
- defining the action contract (params, required fields, confirmation text)
- LLM prompting or orchestration logic

### 5.3 Relay security model (production requirements)

#### 5.3.1 Authentication chain (defense in depth)

Recommended chain:

```
User → AI Fabric (authenticates user/session)
     → Relay (verifies AI Fabric signature)
     → Internal service (re-authorizes user)
```

Critical principle:
- **Internal services must re-authorize** using forwarded user identity/claims.
- Do not rely solely on “AI Fabric already checked”.

#### 5.3.2 User context forwarding (required)

The connector request includes `trace` fields (example: `userId`, `requestId`, `conversationId`, `sessionId`).

Relay must forward enough context so internal services can:
- authorize the user
- write auditable logs
- correlate distributed traces

Guidance:
- Prefer stable internal identifiers (e.g., `userId`) over PII (e.g., email).
- Never log or forward secrets in `trace`.

#### 5.3.3 Rate limiting (required)

Relay should enforce:
- per-user limits (to prevent abuse)
- per-action limits (to protect expensive/mutable operations)

If rate-limited, return:
- `success=false`, `errorCode=RATE_LIMITED`
- a user-safe message
- optional `data.retryAfterSeconds`

#### 5.3.4 Audit logging (required; PII-safe)

Relay must log, at minimum:
- timestamp (ISO 8601)
- `actionId`
- `requestId` (or equivalent correlation id)
- stable `userId` (no email/phone/address)
- outcome: success/failure + `errorCode` (if any)
- latency (ms)

Do not log:
- `params` containing sensitive values (emails, addresses, payment info, tokens)
- full request/response bodies

#### 5.3.5 Network security (required)

AI Fabric → Relay:
- TLS required
- integrity/auth required (HMAC recommended, mTLS optional later)
- replay protection (timestamp + nonce)

Relay → internal services:
- customer choice, but TLS + service auth is recommended

### 5.4 Do actions need to be defined in the Relay?

Not the full action contract (name/params/confirmation text).

But the Relay must know **where to send the call**, unless the customer uses a single internal dispatcher endpoint.

Two safe relay designs:

**A) Relay mapping: `actionId → internal endpoint` (most controlled)**
- Relay config file contains allowlisted actions and their internal routing
- Hosted AI Fabric never sends URLs

**B) Single internal dispatcher (least relay config)**
- Relay forwards everything to one internal endpoint like `POST /actions/execute`
- The customer service routes by `actionId` internally

Avoid:
- A relay that accepts arbitrary URLs from AI Fabric (SSRF risk)

### 5.5 Relay configuration example (illustrative)

```yaml
# relay-config.yml
server:
  port: 8443
  tls:
    enabled: true
    certPath: /etc/relay/tls/cert.pem
    keyPath: /etc/relay/tls/key.pem

aiFabric:
  # used for signature verification / allowlisting
  hmacSecretEnv: AIFABRIC_HMAC_SECRET
  maxClockSkewSeconds: 300

rateLimits:
  perUser:
    windowSeconds: 60
    maxRequests: 100
  perAction:
    create_purchase_order:
      windowSeconds: 60
      maxRequests: 10

actions:
  # Pattern A: explicit mapping (SSRF-safe)
  create_purchase_order:
    endpoint: http://internal-api:8080/orders
    method: POST
    timeoutMs: 5000
  cancel_purchase_order:
    endpoint: http://internal-api:8080/orders/cancel
    method: POST
    timeoutMs: 3000

audit:
  enabled: true
  destination: file
  path: /var/log/relay/audit.jsonl
  retentionDays: 90
```

### 5.6 Deployment patterns

- **Customer-side (recommended)**: Relay inside customer VPC/on‑prem, reachable from AI Fabric
- **Sidecar**: if AI Fabric is deployed into customer environment, run Relay next to it for consistent architecture

---

## 6) Confirmations and multi-step flows (unchanged)

All confirmation semantics remain as in V5:
- missing required params → `CLARIFICATION_REQUIRED`
- confirmation required → `CONFIRMATION_REQUIRED` with a pending action
- `yes/no` intents are resolved into execution by the confirmation pipeline step
- confirmation interceptors can implement flows like retention offers

**Key point:** interceptors operate on action names and pending stacks — they should not depend on the execution backend.

This means:
- a retention offer can chain two connector actions
- or chain local + connector actions
…with the same code.

---

## 7) Optional: documents-only retrieval via the connector

Customers may own retrieval but want AI Fabric to generate answers and orchestrate.

In that model:
- customer provides **documents only**
- AI Fabric does generation (and citations UX)

Suggested endpoint:
- `POST /retrieval/search`

Return:
- an ordered list of documents/chunks with `content`, `source`, `url`, `score`, and optional `vectorSpace`
- no generated answer, prompt fields, model messages, or tool instructions; AI Fabric rejects those
  successful responses with `INVALID_RESPONSE`

This endpoint can be implemented:
- directly by the customer, or
- inside the Relay (which calls internal search/RAG systems)

Runtime integration note:
- AI Fabric supports this via the opt-in module `ai-fabric-retrieval-connector` (as a `RAGProvider`).

Details:
- `../retrieval-vectorization/RETRIEVAL_CONNECTOR_GUIDE.md`
- `changes/Productization/customer-connector-api.openapi.yml`

---

## 8) What this enables (product outcomes)

With one unified action model + connector execution:
- Hosted AI Fabric can be **language-agnostic**
- Customers can adopt with their existing stack (Node/Python/Rails/etc.)
- Your curated packs remain “optimizations”, not domain lock-in
- Integrator/consulting work becomes repeatable: define actions + wire connector + ship

---

## References

- V5 actions + confirmations guide:
  - `./ACTIONS_AND_CONFIRMATION_INTERCEPTORS_GUIDE.md`
- Curated packs:
  - `../runtime-modes-prompts/CURATED_MODES_PACKS_GUIDE.md`
- UI request contract (attachments + position/mode):
  - `../ui-clients/CHAT_CAPABILITIES_UI_MIGRATION_GUIDE.md`

---

## Appendix: Companion artifacts (V1)

- `changes/Productization/customer-connector-api.openapi.yml`
- `../connectors/CUSTOMER_CONNECTOR_IMPLEMENTATION_GUIDE.md`
- `../connectors/RELAY_IMPLEMENTATION_AND_DEPLOYMENT_GUIDE.md`
- `../retrieval-vectorization/RETRIEVAL_CONNECTOR_GUIDE.md`
