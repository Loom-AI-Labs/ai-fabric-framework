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

## Status (as of 2026-07-30)

- **Contract is documented** (OpenAPI + this guide).
- **Implemented in code (opt-in module):** `ai-fabric-retrieval-connector` provides a read-only `RAGProvider` implementation that calls `/retrieval/search`.
- **Response boundary is hardened:** vector-space ownership, document and
  response limits, URL policy, finite scores, deny-by-default metadata, and
  application sanitizers are enforced before context construction.
- **Packaged proof exists:** `examples/real-apps/retrieval-connector-boundary-lab`
  exercises accepted, denied, injected, cross-space, and unsafe responses over
  HTTP.

Opt-in:
- Add dependency `io.github.loom-ai-labs:ai-fabric-retrieval-connector`
- Enable with `ai.retrieval.connector.enabled=true`
- Set `ai.retrieval.connector.base-url` to the customer connector service base URL

When enabled, the module contributes a read-only `RAGProvider` only if the application has not
already defined a `RAGProvider`. This lets customer-owned or deployment-specific providers remain
authoritative while still allowing the connector to replace the default AI Fabric RAG provider in
green-field connector deployments.

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

AI Fabric enforces this boundary. A successful response that includes forbidden top-level generation
fields such as `answer`, `generatedAnswer`, `finalAnswer`, `completion`, `toolInstructions`,
`toolCalls`, `prompt`, `systemPrompt`, `hiddenPrompt`, `messages`, or `instructions` fails closed
with `errorCode=INVALID_RESPONSE`.

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
- `trace`: request correlation plus canonical, verified `authContext` when present

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
    "authContext": {
      "subjectId": "user_789",
      "sessionId": "sess_012",
      "subjectType": "END_USER",
      "authMode": "PUBLIC_RUNTIME_AUTHENTICATED",
      "callerType": "PUBLIC_BROWSER",
      "deploymentId": "dep_123",
      "customerId": "cust_123",
      "tenantId": "tenant_123",
      "issuer": "ai-fabric-runtime",
      "grantedScopes": ["retrieval:search"]
    }
  }
}
```

AI Fabric does not send legacy top-level `trace.userId` or `trace.sessionId` fields. Customer
connectors should read identity and authorization attributes from `trace.authContext`.

### 2.2 Response

Response must be deterministic and shaped as `RetrievalSearchResponse`:
- `success`: boolean
- `message`: user-safe message (optional)
- `errorCode`: stable code for deterministic handling (optional)
- `documents`: required ordered list of documents/chunks on success (may be empty)
- `count`: number of returned documents
- `totalCount`: optional overall count (if known)
- `cursor`: optional next cursor (for pagination)

On `success=true`, AI Fabric expects `documents` to be an array. Each returned document must include
non-empty `id`, non-empty `content`, and numeric `score`. Invalid individual documents are skipped
when at least one valid document remains, with a warning attached to the `RAGResponse`. If a
successful connector response omits `documents`, returns a non-array `documents` value, or returns
only invalid documents, AI Fabric fails closed with `errorCode=INVALID_RESPONSE`. Top-level
generation, prompt, message, or tool-instruction fields are also rejected on successful responses.

Each accepted document is assigned the requested vector space when the field is
absent. A conflicting `vectorSpace` in either the document field or metadata
fails the complete response with `VECTOR_SPACE_MISMATCH`. This authorization
check cannot be disabled.

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
- finite numeric scores; `NaN` and positive or negative infinity fail closed

AI Fabric does not force connector scores into `0..1`. A future
score-normalization contract can address cross-provider comparison separately.

---

## 5) AI Fabric response policy

Every connector response passes a typed response policy before it can become
RAG context or client-visible evidence:

```yaml
ai:
  retrieval:
    connector:
      response-policy:
        max-documents: 50
        max-response-characters: 1000000
        max-document-id-characters: 512
        max-content-characters: 32000
        max-context-characters: 128000
        max-source-characters: 256
        max-url-characters: 2048
        max-vector-space-characters: 128
        max-metadata-entries: 32
        max-metadata-depth: 4
        max-metadata-characters: 8192
        max-message-characters: 512
        max-error-code-characters: 64
        allowed-url-schemes: [https]
        allowed-url-host-suffixes: []
        allowed-metadata-keys: [locale, citation.section]
        unknown-metadata-policy: DROP
```

The effective returned-document limit is the smallest of requested `topK`,
`max-top-k`, and response-policy `max-documents`.

`source`, validated `url`, and `vectorSpace` remain structural fields. They
are not copied into the arbitrary metadata map.

Metadata is deny-by-default:

- only exact allowlisted dotted paths survive;
- unknown paths are dropped by default;
- set `unknown-metadata-policy: REJECT` for a stricter boundary;
- reserved `_aifabric*` paths always fail;
- nested values must be bounded JSON-compatible data.

URLs are citation data only. AI Fabric does not fetch them. `https` is the
default allowed scheme; optional host suffixes match the exact host or a
subdomain.

### 5.1 Application sanitizers

Applications may register one or more ordered
`RetrievalDocumentSanitizer` beans for domain-specific redaction:

```java
@Bean
RetrievalDocumentSanitizer redactExternalEvidence() {
    return (document, context) -> {
        document.setContent(redact(document.getContent()));
        document.setMetadata(Map.of());
        return document;
    };
}
```

The mandatory framework policy runs before and after every application
sanitizer. A custom sanitizer may remove attribution or metadata and may
redact content. It cannot change document identity, score, or vector space,
replace citation attribution, or add metadata that was not present in its
approved input. A null result, exception, or widening attempt fails the whole
response with `SANITIZATION_FAILED`.

---

## 6) Security + compliance (same “fail-closed” model)

### 6.1 Authenticate AI Fabric -> connector

Pick one:
- API key header
- HMAC signature (recommended)
- mTLS (later)

Deny on auth failure.

AI Fabric can send either a static API key header or HMAC signature:

```yaml
ai:
  retrieval:
    connector:
      enabled: true
      base-url: https://relay.customer.example
      api-key:
        header: X-AIFABRIC-API-KEY
        value: ${AI_RETRIEVAL_CONNECTOR_API_KEY:}
      hmac:
        secret: ${AI_RETRIEVAL_CONNECTOR_HMAC_SECRET:}
```

HMAC signing sends timestamp, nonce, and signature headers. Connector implementations should reject
stale timestamps and reused nonces on their side.

### 6.2 Re-authorize the user (defense in depth)

Use `trace.authContext` to enforce:
- tenant boundaries
- knowledge-base access policies

Never rely solely on AI Fabric.

### 6.3 Rate limiting + audit logs

Implement or enforce:
- per-user limits
- per-vectorSpace limits (optional)

Log (PII-safe):
- timestamp
- requestId
- authContext.subjectId or authContext.sessionId
- authContext.tenantId / customerId / deploymentId when relevant
- vectorSpace
- outcome (success/errorCode)
- latency

Do not log full `query` content if it can contain PII; if you must log, hash it or store a redacted/truncated form.

---

## 7) Migration checklist

Connector owners upgrading from the original V1 boundary must verify:

1. Every returned document either omits `vectorSpace` or returns the exact
   requested value.
2. Citation URLs use configured schemes and, when configured, allowed hosts.
3. Metadata needed by the application is listed in
   `allowed-metadata-keys`.
4. No connector returns reserved `_aifabric*` metadata.
5. Document, metadata, message, count, and overall response sizes fit the
   configured limits.
6. Scores are finite JSON numbers, not numeric strings.
7. Connector error codes use bounded uppercase identifiers such as
   `ACCESS_DENIED`.

No migration to `AIIndexDocument` is required. `AIIndexDocument` remains the
durable write/indexing work contract; `RAGResponse.RAGDocument` remains
request-scoped retrieved evidence.

---

## 8) Testing checklist

- Always returns **documents only** (no answer fields, no generation).
- Forbidden generation/prompt/tool fields on a successful response fail closed with `INVALID_RESPONSE`.
- Missing vector space is normalized; conflicting vector space fails the
  complete response with `VECTOR_SPACE_MISMATCH`.
- Metadata allowlist, `DROP`/`REJECT`, and reserved-key behavior are covered.
- Unsafe URL schemes and disallowed hosts fail closed.
- Document count, body, field, metadata, and context limits are exercised.
- Non-finite scores and non-numeric score strings are rejected.
- Custom sanitizers can redact but cannot widen approved evidence.
- Unknown `vectorSpace` fails closed (`success=false`, `errorCode=NOT_FOUND` or standardized `VECTOR_SPACE_NOT_FOUND` if adopted).
- `trace.authContext` drives user/tenant re-authorization; do not depend on top-level `trace.userId`.
- Malformed success responses fail closed with `INVALID_RESPONSE`.
- Filters are applied correctly (no tenant bleed).
- Ordering is stable (highest score first).
- Pagination via `cursor` works (when implemented).
- PII-safe logs (no raw queries or sensitive content).

Run the packaged boundary proof:

```bash
mvn -B --no-transfer-progress -f examples/real-apps/pom.xml \
  -pl retrieval-connector-boundary-lab -am test
```
