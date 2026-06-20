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

Safety constraints for the applied hint:
- max length bound
- no leading/trailing whitespace, tabs, newlines, or consecutive whitespace
- no `@` (email marker)
- no prompt/markup/control-like punctuation such as `:`, braces, angle brackets, quotes, or backticks
- only letters, numbers, single spaces, and conservative identifier punctuation (`-`, `_`, `.`, `#`, `/`, `'`)

Observability:
- The orchestrator writes `retrievalQueryHintApplied=true|false` into metadata.

## Consequences
Positive:
- Improves recall for identifier-heavy queries (SKUs, ids, product names) without requiring additional RAG calls.
- Keeps behavior deterministic and transparent in debug.

Negative / tradeoffs:
- The hint is intentionally **not** applied when multiple retrieval intents exist to avoid cross-intent contamination.

## Implementation
- `ai.fabric.intent.orchestration.pipeline.steps.RetrievalQueryHintSupport`
  - `resolveValidRetrievalQueryHint(...)`
  - `applyRetrievalQueryHint(...)`
