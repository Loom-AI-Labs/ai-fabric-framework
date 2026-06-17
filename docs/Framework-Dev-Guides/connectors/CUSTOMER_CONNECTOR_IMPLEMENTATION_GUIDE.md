# Customer Connector Implementation Guide (Actions + Optional Retrieval) — V1

This guide explains how a customer (or your integrator team) implements the **Customer Connector API** that AI Fabric calls to:
- execute **customer-defined actions** (`POST /actions/execute`)
- optionally provide **documents-only retrieval** (`POST /retrieval/search`)

Reference:
- Architecture + contracts: `../actions-governance/ACTIONS_CONNECTOR_AND_RELAY_GUIDE.md`
- OpenAPI spec: `changes/Productization/customer-connector-api.openapi.yml`
- Retrieval details: `../retrieval-vectorization/RETRIEVAL_CONNECTOR_GUIDE.md`

---

## Status (as of 2026-02-13)

- **Implemented in AI Fabric:** `ai-infrastructure-actions-connector` calls `/actions/execute`, parses the `ActionResult` contract, generates idempotency keys, and performs bounded retries.
- **Implemented in AI Fabric:** `ai-infrastructure-retrieval-connector` calls `/retrieval/search` and returns documents/chunks via the `RAGProvider` contract.
- **Reference implementation available:** `ai-infrastructure-relay` implements the Customer Connector API with inbound auth + replay protection + rate limits + idempotency (in-memory by default; Redis backend supported).

---

## 1) Core requirements (what you must implement)

### 1.1 `POST /actions/execute`

You must implement:
- deterministic routing by `actionId` (no arbitrary URLs)
- authorization **inside your environment** (defense in depth)
- idempotency for write actions
- a stable `ActionResult` response body

Request fields:
- `actionId`: stable identifier matching the validated action catalog
- `params`: JSON object containing action params
- `idempotencyKey`: required for write actions (`WRITE_ONLY`, `READ_WRITE`)
- `trace`: request correlation + user identity context

### 1.2 (Optional) `POST /retrieval/search` (documents only)

If you want to own retrieval:
- return **documents/chunks only** (no generated answer)
- AI Fabric will generate and orchestrate

---

## 2) Security model (fail-closed)

### 2.1 Authenticate AI Fabric → connector

Supported patterns (pick one):
- **API key header** (simple)
- **HMAC signature** (recommended): timestamp + nonce + signature over request body
- **mTLS** (later/enterprise)

Rule:
- If auth fails, **deny the request**. Do not execute partially.

### 2.2 Re-authorize the user (defense in depth)

AI Fabric will pass a stable `trace.userId` (and other trace fields).
Your connector (or the internal services it calls) should re-authorize:
- Is this user allowed to execute this action?
- Is this user allowed to execute it with these params (resource-level checks)?

Never rely on “AI Fabric already checked”.

### 2.3 Rate limiting

If you expose the connector directly (without a relay), implement:
- per-user limits
- per-action limits (especially for write actions)

If you deploy a Relay, rate limiting should live there by default.

### 2.4 Audit logging (PII-safe)

Log at least:
- timestamp
- `actionId`
- `trace.requestId`
- stable `trace.userId` (avoid email/phone/address)
- outcome: success/failure + `errorCode`
- latency

Do not log:
- full request bodies
- sensitive params (emails, shippingAddress, tokens, payment details)

---

## 3) Idempotency (required for write actions)

AI Fabric generates `idempotencyKey` for write actions.

Your connector must:
- store `(idempotencyKey, actionId, paramsFingerprint, ActionResult, expiresAt)`
- on duplicate key:
  - return the **same** `ActionResult`
  - do not apply side effects twice
- on same key + different params:
  - return `success=false`, `errorCode=IDEMPOTENCY_CONFLICT`

Recommended TTL:
- minimum: 24 hours
- recommended: 48 hours

Storage options:
- Redis (recommended for production)
- DB table + cleanup job
- in-memory (dev only)

---

## 4) Error contract (stable `errorCode`)

Return a body shaped like `ActionResult` for both success and handled failures.

Guidelines:
- `message` must be user-safe
- `errorCode` must be stable
- avoid stack traces and internal error details

Recommended `errorCode`s:
- `INVALID_PARAMETER`
- `UNAUTHORIZED`
- `FORBIDDEN`
- `NOT_FOUND`
- `CONFLICT`
- `BUSINESS_RULE_VIOLATION`
- `RATE_LIMITED`
- `TIMEOUT`
- `SERVICE_UNAVAILABLE`
- `IDEMPOTENCY_CONFLICT`
- `ACTION_EXECUTION_FAILED`

AI Fabric hosted runtime derives retry behavior from `errorCode` + idempotency safety.

Note:
- The `ActionResult` contract does **not** include a `retriable` boolean. Do not add custom fields; keep the response strictly compatible with the OpenAPI schema.

---

## 5) Payload rules (`ActionResult.data`)

AI Fabric supports two payload shapes:

### 5.1 Object payload (default)

Return any object fields, but do **not** use reserved list keys:
- `_items`, `_count`, `_totalCount`, `_cursor`

### 5.2 List payload (search/list results)

If you return a list-style result, you must use the reserved keys:
- `_items`: array
- `_count`: integer and must equal `_items.length`
- optional `_totalCount`, `_cursor` for pagination

Do not invent custom pagination keys.

---

## 6) Minimal implementation sketch (reference)

Your connector can be implemented in any stack. Two safe routing patterns:

### Pattern A — `actionId → handler` mapping

- you define a registry of allowed action ids
- each action maps to internal code/service calls
- prevents SSRF and prevents LLM-driven URL selection

### Pattern B — single internal dispatcher

- `/actions/execute` forwards to one internal service
- the internal service routes by `actionId`

Rule:
- do not accept arbitrary URLs from AI Fabric

---

## 7) Testing checklist (before shipping to customers)

### Actions
- Unknown `actionId` → `success=false`, `errorCode=NOT_FOUND` (or `ACTION_NOT_FOUND` if you standardize it)
- Missing required param → `success=false`, `errorCode=INVALID_PARAMETER`
- Idempotency: same key twice → same `ActionResult` (no duplicate side effects)
- Idempotency conflict: same key + different params → `IDEMPOTENCY_CONFLICT`
- Rate limiting: burst requests → `RATE_LIMITED`
- PII safety: `message` never leaks secrets, logs don’t contain sensitive params

### Retrieval (optional)
- Always returns documents/chunks only (no generated answers)
- Ordering is stable (score descending)
- Cursor pagination works (when implemented)
