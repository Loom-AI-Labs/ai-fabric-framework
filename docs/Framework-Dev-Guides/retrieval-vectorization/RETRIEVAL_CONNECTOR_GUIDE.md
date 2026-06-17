# Retrieval Connector Guide (Documents-Only) — V1

This guide specifies the optional **documents-only retrieval** boundary for AI Fabric.

If a customer wants to own retrieval (their vector DB / search system), they can implement:
- `POST /retrieval/search`

AI Fabric remains responsible for:
- orchestration
- prompt/mode optimizations
- answer generation + citations UX

Reference:
- Architecture overview: `../actions-governance/ACTIONS_CONNECTOR_AND_RELAY_GUIDE.md`
- OpenAPI contract: `changes/Productization/customer-connector-api.openapi.yml`
- Connector implementation guide: `../connectors/CUSTOMER_CONNECTOR_IMPLEMENTATION_GUIDE.md`

---

## Status (as of 2026-02-12)

- **Contract is documented** (OpenAPI + this guide).
- **Implemented in code (opt-in module):** `ai-infrastructure-retrieval-connector` provides a `RAGProvider` implementation that calls `/retrieval/search`.

Opt-in:
- Add dependency `com.ai.fabric:ai-infrastructure-retrieval-connector`
- Enable with `ai.retrieval.connector.enabled=true`

---

## 1) Non-negotiable rule: documents only

`/retrieval/search` MUST return **documents/chunks only**:
- raw text (`content`)
- stable identifiers (`id`)
- scoring (`score`)
- optional metadata (`source`, `url`, `vectorSpace`, `metadata`)

It MUST NOT return:
- a generated answer
- tool instructions / “what the model should do”
- hidden prompts

Reason:
- AI Fabric needs a clean, deterministic retrieval boundary.
- Generation remains inside AI Fabric so curated modes/packs stay consistent.

---

## 2) Endpoint contract

### 2.1 Request: `POST /retrieval/search`

Key fields:
- `query`: the search query (embedding query / semantic query) produced by AI Fabric
- `vectorSpace`: customer-owned vector space name (index/collection selector)
- `topK`: number of documents/chunks to return (default 10)
- `cursor`: opaque pagination cursor (optional)
- `filters`: optional customer-defined filters (optional)
- `trace`: request correlation + stable user/session identifiers

Example:

```json
{
  "query": "return policy for AirPods Pro",
  "vectorSpace": "policy",
  "topK": 10,
  "cursor": null,
  "filters": { "locale": "en_US" },
  "trace": {
    "requestId": "req_123",
    "conversationId": "chat_456",
    "userId": "user_789",
    "sessionId": "sess_012"
  }
}
```

### 2.2 Response

Response must be deterministic and shaped as `RetrievalSearchResponse`:
- `success`: boolean
- `message`: user-safe message (optional)
- `errorCode`: stable code for deterministic handling (optional)
- `documents`: ordered list of documents/chunks (may be empty)
- `count`: number of returned documents
- `totalCount`: optional overall count (if known)
- `cursor`: optional next cursor (for pagination)

Example (success):

```json
{
  "success": true,
  "documents": [
    {
      "id": "policy#returns#p3",
      "content": "You can return items within 30 days...",
      "score": 0.91,
      "source": "policy",
      "url": "https://example.com/policy/returns",
      "vectorSpace": "policy",
      "metadata": { "locale": "en_US" }
    }
  ],
  "count": 1,
  "totalCount": null,
  "cursor": null
}
```

Example (handled failure):

```json
{
  "success": false,
  "errorCode": "FORBIDDEN",
  "message": "You do not have access to this knowledge base.",
  "documents": [],
  "count": 0,
  "totalCount": null,
  "cursor": null
}
```

---

## 3) Vector spaces (customer-owned)

Customers define their own `vectorSpace` names (examples: `products`, `policy`, `faq`, `support_tickets`).

Recommendations:
- Treat `vectorSpace` as an allowlisted identifier (fail-closed on unknown).
- Keep names stable (do not use user-provided strings directly as collection names).
- Use `filters` for dynamic partitioning (tenantId, locale, brand, region), not dynamic vectorSpace names.

---

## 4) Scoring + ordering requirements

The connector defines its own score scale, but it MUST be:
- monotonic (higher score = more relevant)
- consistent for ordering within a response

AI Fabric expects:
- `documents` ordered by relevance (descending score)

---

## 5) Security + compliance (same “fail-closed” model)

### 5.1 Authenticate AI Fabric → connector

Pick one:
- API key header
- HMAC signature (recommended)
- mTLS (later)

Deny on auth failure.

### 5.2 Re-authorize the user (defense in depth)

Use `trace.userId` (stable, non‑PII) to enforce:
- tenant boundaries
- knowledge-base access policies

Never rely solely on AI Fabric.

### 5.3 Rate limiting + audit logs

Implement or enforce:
- per-user limits
- per-vectorSpace limits (optional)

Log (PII-safe):
- timestamp
- requestId
- userId
- vectorSpace
- outcome (success/errorCode)
- latency

Do not log full `query` content if it can contain PII; if you must log, hash it or store a redacted/truncated form.

---

## 6) Testing checklist

- Always returns **documents only** (no answer fields, no generation).
- Unknown `vectorSpace` fails closed (`success=false`, `errorCode=NOT_FOUND` or standardized `VECTOR_SPACE_NOT_FOUND` if adopted).
- Filters are applied correctly (no tenant bleed).
- Ordering is stable (highest score first).
- Pagination via `cursor` works (when implemented).
- PII-safe logs (no raw queries or sensitive content).
