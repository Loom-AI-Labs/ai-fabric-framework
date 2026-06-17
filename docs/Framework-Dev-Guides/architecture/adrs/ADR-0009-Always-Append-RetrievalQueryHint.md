# ADR-0009 — Always Append `retrievalQueryHint` (When Valid)

## Status
Accepted

## Context
Intent extraction may produce an optional `metadata.retrievalQueryHint` (short keywords/identifiers) to improve retrieval recall.

We need a standard, production-safe way to apply this hint without:
- leaking PII (emails/phones/addresses),
- polluting queries with multi-line/prompt artifacts,
- mixing hints across multiple retrieval intents in compound requests.

## Decision
When the request contains **exactly one** retrieval intent and the extractor provided a **safe** `retrievalQueryHint`, the orchestrator **appends the hint** to the retrieval query.

Safety constraints:
- max length bound
- no newlines
- no `@` (email marker)
- no consecutive whitespace

Observability:
- The orchestrator writes `retrievalQueryHintApplied=true|false` into metadata.

## Consequences
Positive:
- Improves recall for identifier-heavy queries (SKUs, ids, product names) without requiring additional RAG calls.
- Keeps behavior deterministic and transparent in debug.

Negative / tradeoffs:
- The hint is intentionally **not** applied when multiple retrieval intents exist to avoid cross-intent contamination.

## Implementation
- `com.ai.infrastructure.intent.orchestration.pipeline.steps.IntentHandlingStep`
  - `resolveValidRetrievalQueryHint(...)`
  - `applyRetrievalQueryHint(...)`

