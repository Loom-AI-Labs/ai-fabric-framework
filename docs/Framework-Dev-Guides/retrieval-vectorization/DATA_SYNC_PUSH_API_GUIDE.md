# Data Sync Push API (Managed Vector DB Ingestion) — V1

This guide specifies the **push-based ingestion API** used when AI Fabric manages the vector database.

It enables customers (or your integrator/Shopify app) to:
- upsert content into a customer-owned **vectorSpace**
- delete content by `(vectorSpace, id)`
- batch multiple operations

This is **domain-agnostic**: AI Fabric does not ship commerce entities/logic. The customer controls:
- vector space naming (via `ai-entity-config.yml`)
- what fields are indexed (via searchable fields config)
- what metadata is persisted (via metadata fields config)

Related:
- External documents-only retrieval (customer-owned RAG): `./RETRIEVAL_CONNECTOR_GUIDE.md`
- Productization plan: `changes/Productization/PRODUCTIZATION_IMPLEMENTATION_PLAN.md`

---

## Status (as of 2026-06-19)

- **Implemented in code (opt-in module):** `ai-fabric-data-sync`
  - REST endpoints under `/api/ai/data-sync/*`
  - Typed YAML-only projection using searchable fields + metadata fields
  - Canonical `AIIndexDocument` handoff through `AIEntityIndexingGateway`
  - Fail-closed access control via verified `trace.authContext` → `AIAccessControlService`
  - Batch preflight authorization that reuses the approved decisions during execution
  - Trusted platform-internal policy bypass is explicit opt-in with
    `ai.data-sync.allow-trusted-platform-internal-sync-bypass=true`

Opt-in:
- Add dependency `io.github.loom-ai-labs:ai-fabric-data-sync`
- Enable with `ai.data-sync.enabled=true`

Prerequisites:
- Managed vector DB configured (`ai.vector-db.type=...`)
- Embeddings enabled (`ai.service.features.enable-embeddings=true`, the default)
- `EntityAccessPolicy` bean present for non-platform callers (fail-closed)
- For default production posture, keep
  `ai.data-sync.allow-trusted-platform-internal-sync-bypass=false` and grant/deny through
  `EntityAccessPolicy`. Enable the bypass only behind a trusted backend/runtime boundary that verifies
  and injects `trace.authContext`.

Push data sync is a trusted YAML-only ingress. It does not reflect application entity classes and it
does not bypass projection validation. Every accepted upsert becomes the same class-free,
destination-specific index document used by annotation lifecycle and migration.

---

## 1) When to use this (and when NOT to)

Use the push API when:
- AI Fabric hosts/manages the vector DB (Lucene/Qdrant/Pinecone/…)
- customer wants a **turnkey** managed retrieval option

Do NOT use the push API when:
- the customer owns retrieval and implements `POST /retrieval/search` (documents-only retrieval connector)

---

## 2) Endpoint summary

### 2.1 List vector spaces

- `GET /api/ai/data-sync/vector-spaces`

Returns the configured vector spaces (entity types) from `ai-entity-config.yml`.

### 2.2 Upsert (single)

- `POST /api/ai/data-sync/upsert`

Required fields:
- `vectorSpace`
- `id`
- `trace.authContext.subjectId`
- **either** `content` **or** `entity`

### 2.3 Delete (single)

- `POST /api/ai/data-sync/delete`

Required fields:
- `vectorSpace`
- `id`
- `trace.authContext.subjectId`

### 2.4 Batch

- `POST /api/ai/data-sync/batch`

Required fields:
- `trace.authContext.subjectId`
- `operations[]`

Batch semantics:
- **Fail-closed preflight:** every operation is validated and authorized before vector writes/deletes start.
- If any operation is denied or access evaluation fails closed, **no operations execute**.
- The approved preflight decisions are reused during execution; access control is not re-run per operation after writes start.
- Vector/embedding failures after successful preflight are returned as per-operation failures with batch message `Completed with failures`.

---

## 3) Request/response contracts

### 3.1 Trace object

`trace` is required and is used for access control + auditing.

```json
{
  "requestId": "optional",
  "metadata": { "tenantId": "m_123" },
  "authContext": {
    "subjectId": "system_shopify_sync",
    "subjectType": "SYSTEM_PROCESS",
    "authMode": "PRIVATE_RUNTIME_BACKEND_MEDIATED",
    "callerType": "SYSTEM_PROCESS",
    "sessionId": "optional",
    "deploymentId": "dep_123",
    "customerId": "cus_123",
    "tenantId": "m_123",
    "issuer": "runtime-shopify-sync",
    "grantedScopes": ["data-sync:upsert", "data-sync:delete"]
  }
}
```

`trace.authContext` is the canonical verified caller identity. Do not use legacy top-level
`trace.userId`; the runtime ignores it for secure data-sync authorization.

### 3.2 Upsert request (single)

```json
{
  "vectorSpace": "product",
  "id": "SKU-123",
  "entity": {
    "title": "Sony WH-1000XM5",
    "description": "Noise cancelling headphones",
    "price": 399
  },
  "metadata": { "locale": "en_US" },
  "trace": {
    "requestId": "req_1",
    "authContext": {
      "subjectId": "system_shopify_sync",
      "subjectType": "SYSTEM_PROCESS",
      "authMode": "PRIVATE_RUNTIME_BACKEND_MEDIATED",
      "callerType": "SYSTEM_PROCESS",
      "issuer": "runtime-shopify-sync",
      "grantedScopes": ["data-sync:upsert"]
    }
  }
}
```

Response:
- `success`
- `errorCode` (when `success=false`)
- `vectorId` (when `success=true`)

### 3.3 Delete request (single)

```json
{
  "vectorSpace": "product",
  "id": "SKU-123",
  "trace": {
    "requestId": "req_2",
    "authContext": {
      "subjectId": "system_shopify_sync",
      "subjectType": "SYSTEM_PROCESS",
      "authMode": "PRIVATE_RUNTIME_BACKEND_MEDIATED",
      "callerType": "SYSTEM_PROCESS",
      "issuer": "runtime-shopify-sync",
      "grantedScopes": ["data-sync:delete"]
    }
  }
}
```

### 3.4 Batch request

```json
{
  "trace": {
    "requestId": "req_batch_1",
    "authContext": {
      "subjectId": "system_shopify_sync",
      "subjectType": "SYSTEM_PROCESS",
      "authMode": "PRIVATE_RUNTIME_BACKEND_MEDIATED",
      "callerType": "SYSTEM_PROCESS",
      "issuer": "runtime-shopify-sync",
      "grantedScopes": ["data-sync:upsert", "data-sync:delete"]
    }
  },
  "operations": [
    { "type": "UPSERT", "vectorSpace": "product", "id": "SKU-1", "content": "..." },
    { "type": "DELETE", "vectorSpace": "product", "id": "SKU-2" }
  ]
}
```

---

## 4) Normalization rules (deterministic + bounded)

When `content` is provided:
- it is trimmed and passed through the configured trusted projection

When `entity` is provided:
- AI Fabric builds content from the configured searchable fields in `ai-entity-config.yml`
- metadata is enriched only from configured metadata fields and trusted identity metadata

Define a YAML-only vector space explicitly:

```yaml
ai-entities:
  product:
    indexing:
      enabled: true
      max-characters: 8000
    analysis:
      enabled: false
    searchable-fields:
      - name: title
        destinations: [SEMANTIC_SEARCH, RAG_CONTEXT]
        preprocessing: NORMALIZE
        priority: 100
        required: true
      - name: description
        destinations: [SEMANTIC_SEARCH, RAG_CONTEXT]
        preprocessing: CLEAN
        priority: 80
    metadata-fields:
      - name: tenantId
        data-type: ID
        destinations: [VECTOR_METADATA]
        priority: 100
        required: true
      - name: locale
        data-type: STRING
        destinations: [VECTOR_METADATA, API_RESPONSE]
        priority: 60
```

The map key is the canonical YAML-only entity type. Nested `entity-type`, generic `features`,
`indexable`, `auto-process`, CRUD work booleans, and `includeInSearch` are not part of the current
schema.

Projection priority controls ordering and bounded retention only. It does not alter vector similarity.
Required-field and PII failures reject the request before provider execution.

Bounds (fail-closed):
- `ai.data-sync.maxContentChars` (default `8000`)
- `ai.data-sync.maxFieldValueChars` (default `2000`)
- `ai.data-sync.maxMetadataKeys` (default `75`)

---

## 5) Access control model (fail-closed)

The module uses:
- `EntityAccessPolicy` (customer-implemented)
- `AIAccessControlService` (framework)

Policy inputs:
- `resourceId = "vectorSpace:{vectorSpace}"`
- `operationType = "WRITE"` (upsert) or `"DELETE"` (delete)
- `authContext` is populated from `trace.authContext`
- metadata includes `vectorSpace`, `entityId`, `operationType`, verified-auth evidence, and any `trace.metadata`

If policy is missing or throws:
- deny the request (fail-closed)

Access-denied responses include structured metadata:
- `identitySource`
- `accessDecisionSource`
- `accessEvaluationStatus`
- `accessEvaluationFailure` when authorization failed closed because evaluation threw

Batch access-denied responses also include:
- `deniedOperations` for backward-compatible human-readable entries
- `deniedOperationDetails` for structured release/debug evidence

Trusted platform-internal sync can bypass the policy only when
`ai.data-sync.allow-trusted-platform-internal-sync-bypass=true` and all of these are true:
- `subjectType=SYSTEM_PROCESS`
- `authMode=PRIVATE_RUNTIME_BACKEND_MEDIATED`
- `callerType=SYSTEM_PROCESS`
- `subjectId` starts with `system:platform-`
- `issuer` starts with `platform-`
- `deploymentId` is present
- `grantedScopes` includes `data-sync:upsert` or `data-sync:delete` for the requested operation

The bypass is disabled by default because this API accepts `trace.authContext` in the request DTO.
Default behavior routes platform-shaped contexts through `AIAccessControlService`, preserving the
fail-closed policy boundary.

---

## 6) Error Semantics

| Error code | HTTP status | Notes |
| --- | --- | --- |
| `INVALID_REQUEST` | `400` | Missing request fields, invalid chunk identity, or normalization bounds failure. |
| `BATCH_TOO_LARGE` | `400` | `operations.length` exceeds `ai.data-sync.maxBatchSize`. |
| `VECTOR_SPACE_NOT_FOUND` | `404` | `vectorSpace` is not configured in `ai-entity-config.yml`. |
| `VECTOR_SPACE_NOT_INDEXABLE` | `400` | Upsert requested for a non-indexable vector space. |
| `ACCESS_DENIED` | `403` | Missing verified auth context, policy denial, or fail-closed policy evaluation. |
| `PROJECTION_REJECTED` | `400` | Required field, bounds, PII, destination, or projection validation failed. |
| `INDEXING_SUBMISSION_FAILED` | `500` | Durable indexing submission could not be accepted. |
| `INDEXING_RETRYABLE` | `503` | Work is durable but synchronous provider execution failed and is pending retry. |
| `INDEXING_PERMANENT` | `500` | Synchronous provider work exhausted its retry policy and requires operator review. |

Provider failures are not converted into success. The response includes bounded outcome metadata and
the durable queue retains retry/dead-letter evidence.

### Reconcile durable indexing work

An `INDEXING_RETRYABLE` response means the source operation was accepted and the indexing work is
durable. It does not mean the vector mutation failed permanently, and callers must not blindly submit
the same source mutation again.

Read `metadata.indexingWorkId` from the operation response and query the work through the in-process
`IndexingWorkQuery` contract:

```java
IndexingWorkStatus work = indexingWorkQuery
    .findByWorkId(indexingWorkId)
    .orElseThrow(() -> new IllegalStateException(
        "Indexing work no longer exists: " + indexingWorkId
    ));

if (work.isInProgress()) {
    // Keep the application operation pending and poll again with a bounded delay.
} else if (work.isSuccessfulTerminal()) {
    // COMPLETED applied this work. SUPERSEDED means newer work owns the entity state.
} else if (work.requiresOperatorReview()) {
    // DEAD_LETTER: expose a safe failure and route it to application operations.
}
```

The query returns the public lifecycle state, entity identity, work type, source operation, strategy,
retry counters, bounded failure codes, correlation ID, and lifecycle timestamps. It intentionally
does not expose the serialized source payload, provider result payload, persistence entity, or worker
node.

`IndexingWorkQuery` is a framework Java contract, not a public framework HTTP endpoint. An application
or platform may expose a private status route, but it must authenticate the caller and authorize the
work item before returning entity identity or diagnostics. For a shared or tenant-facing route, derive
tenant scope from trusted backend identity; never trust tenant identity supplied by the browser.

Vector existence is not a replacement for this query. Existence cannot prove that an update reached
the expected revision, that a delete completed, or that the observed vector belongs to this work item.

---

## 7) Configuration

Enable:

```properties
ai.data-sync.enabled=true
ai.data-sync.basePath=/api/ai/data-sync
```

Tuning:

```properties
ai.data-sync.maxBatchSize=200
ai.data-sync.maxContentChars=8000
ai.data-sync.maxFieldValueChars=2000
ai.data-sync.maxMetadataKeys=75
```

The default base path is `/api/ai/data-sync`. Spring Boot relaxed binding also accepts kebab-case
properties such as `ai.data-sync.base-path` and `ai.data-sync.max-batch-size`.

Indexing queue/worker tuning belongs under `ai.indexing.*`, including max retries, visibility
timeout, worker delays/batch sizes, stuck-work reset, and completed/dead-letter retention.

---

## 8) Notes for Shopify sync

Recommended:
- Define a `product` vector space in `ai-entity-config.yml` with searchable fields matching Shopify fields you care about.
- Push product updates via `/api/ai/data-sync/upsert` from your Shopify app backend.
- Use `trace.metadata.tenantId` (or similar) and enforce tenant boundaries in `EntityAccessPolicy`.
