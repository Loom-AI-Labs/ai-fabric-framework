# NotebookLM Video Script: From Existing Data To Continuous AI Evidence

## Production Instruction

Produce an eleven-minute technical video for Java and Spring Boot developers. Use only this script.
Show one continuing Support Knowledge Assistant and make source ownership, stable identity, security,
and recovery visible.

## Opening

Adding semantic search to an existing application has two time horizons. First, historical rows must
be backfilled. Then every trusted create, update, and delete must keep derived AI evidence aligned
with current application truth. Solving only the first horizon creates a demo that becomes stale the
moment a real user changes data.

AI Fabric separates migration from live Data Sync.

## Initial Backfill Flow

Describe this diagram:

```text
existing application rows
       |
       v
migration job -- bounded scan, filters, pause/resume
       |
       v
durable indexing queue -- retry and failure state
       |
       v
embedding provider -> vector provider
```

Migration establishes initial derived evidence. Job completion means scanning and enqueueing
finished. Queue drain and expected retrievable vectors prove readiness.

## Live Synchronization Flow

```text
authenticated domain request
       |
application verifies user, tenant, source ownership, allowed dataset
       |
application transaction changes source row
       |
Data Sync checks access and builds an approved AIIndexDocument
       |
source row + projected queue work commit together
       |
after commit: embed and upsert/delete stable ID, with retry/dead-letter evidence
```

The browser supplies article content and intent to create, update, or delete. It does not supply a
trusted tenant, auth context, arbitrary vector space, or provider route. The backend derives those
values from verified identity and persisted source state.

## Stable Identity

Use the source article ID as the whole-record logical ID. A source version and content fingerprint
provide retry evidence. Updating article `article-live-sync` upserts that same logical ID, replacing
old text. Deleting it addresses the same ID. Chunk IDs are useful for document chunks but would
intentionally create distinct effective IDs, so omit them for this whole-record example.

## Access And Bypass

AI Fabric Data Sync requires verified auth context and delegates to `EntityAccessPolicy`. The
framework includes an opt-in platform/system bypass, but it remains disabled unless a trusted
backend verifies and injects the exact system identity and scope. A frontend cannot prove trust by
sending system-shaped JSON.

The Support Knowledge Assistant exposes domain-specific endpoints and denies the raw Data Sync DTO
route. Its policy permits only `vectorSpace:knowledge-article` and exact upsert/delete scopes.

## Normalization And Privacy

The application projects approved article fields. Internal notes never enter Data Sync. The
normalizer uses configured searchable and metadata fields, bounds content and field lengths, and
fails closed on invalid or oversized input. The durable queue stores the approved class-free
projection, not the source entity. Embedding and vector failures remain explicit.

## Consistency And Recovery

Authorization, projection, and durable handoff happen before source commit. When the application
turns one of those failures into an exception, the source transaction and queue row roll back
together.

Embedding and vector mutation happen only after commit. A provider failure cannot undo committed
business data; it leaves durable retryable work and can move to a visible dead letter. Stable IDs,
correlation traces, idempotent retries, ordering state, and reconciliation remain necessary.

A reconciliation endpoint reloads source rows, builds bounded operations, and reports every result.
One failed operation does not become a generic success. An oversized batch is rejected before side
effects.

## Incorrect Architecture

An incorrect design publishes the raw Data Sync controller and lets the browser submit tenant and
auth-context fields. Another uses a random vector ID for every update. The first permits identity
forgery; the second leaves stale versions retrievable and makes delete unreliable.

## Visible Failure

When one row exceeds normalization limits, the batch reports one success and one
`PROJECTION_REJECTED`. Source rows remain unchanged. Operators can identify the failed stable ID,
correct it, and retry it. No fallback invents evidence and no success card hides the failure.

## Lab Bridge

In PROD-05, you will implement that trusted boundary, prove stable create/update/delete behavior,
deny raw access, and exercise reconciliation failures with local ONNX and Lucene. PROD-06 then turns
the resulting evidence behavior into a deterministic RAG quality gate.
