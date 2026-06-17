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

## Status (as of 2026-02-12)

- **Implemented in code (opt-in module):** `ai-infrastructure-data-sync`
  - REST endpoints under `/api/ai/data-sync/*`
  - Normalization using `ai-entity-config.yml` (searchable fields + metadata fields)
  - Fail-closed access control via `EntityAccessPolicy` → `AIAccessControlService`

Opt-in:
- Add dependency `com.ai.fabric:ai-infrastructure-data-sync`
- Enable with `ai.data-sync.enabled=true`

Prerequisites:
- Managed vector DB configured (`ai.vector-db.type=...`)
- `EntityAccessPolicy` bean present (fail-closed)

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
- `trace.userId`
- **either** `content` **or** `entity`

### 2.3 Delete (single)

- `POST /api/ai/data-sync/delete`

Required fields:
- `vectorSpace`
- `id`
- `trace.userId`

### 2.4 Batch

- `POST /api/ai/data-sync/batch`

Required fields:
- `trace.userId`
- `operations[]`

Batch semantics:
- **Fail-closed on access control:** if any operation is denied, **no operations execute**.
- Returns per-operation results when allowed to proceed.

---

## 3) Request/response contracts

### 3.1 Trace object

`trace` is required and is used for access control + auditing.

```json
{
  "userId": "system_shopify_sync",
  "sessionId": "optional",
  "requestId": "optional",
  "metadata": { "tenantId": "m_123" }
}
```

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
  "trace": { "userId": "system_shopify_sync", "requestId": "req_1" }
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
  "trace": { "userId": "system_shopify_sync", "requestId": "req_2" }
}
```

### 3.4 Batch request

```json
{
  "trace": { "userId": "system_shopify_sync", "requestId": "req_batch_1" },
  "operations": [
    { "type": "UPSERT", "vectorSpace": "product", "id": "SKU-1", "content": "..." },
    { "type": "DELETE", "vectorSpace": "product", "id": "SKU-2" }
  ]
}
```

---

## 4) Normalization rules (deterministic + bounded)

When `content` is provided:
- it is used as-is (trimmed)

When `entity` is provided:
- AI Fabric builds content from the configured searchable fields in `ai-entity-config.yml`
- metadata is enriched using configured metadata fields (includeInSearch=true)

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
- metadata includes `vectorSpace`, `entityId`, and any `trace.metadata`

If policy is missing or throws:
- deny the request (fail-closed)

---

## 6) Configuration

Enable:

```properties
ai.data-sync.enabled=true
```

Tuning:

```properties
ai.data-sync.maxBatchSize=200
ai.data-sync.maxContentChars=8000
ai.data-sync.maxFieldValueChars=2000
ai.data-sync.maxMetadataKeys=75
```

---

## 7) Notes for Shopify sync

Recommended:
- Define a `product` vector space in `ai-entity-config.yml` with searchable fields matching Shopify fields you care about.
- Push product updates via `/api/ai/data-sync/upsert` from your Shopify app backend.
- Use `trace.metadata.tenantId` (or similar) and enforce tenant boundaries in `EntityAccessPolicy`.

