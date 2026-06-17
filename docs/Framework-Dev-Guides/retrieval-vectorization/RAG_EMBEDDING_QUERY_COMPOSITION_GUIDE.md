# RAG Embedding Query Composition (Pinned Targets Expansion)

## Why this exists
We separate **what the user asked** from **what we embed** for vector search.

This prevents two common failures:
- **Pseudo‑DSL “optimized queries”** hurting semantic retrieval quality.
- **Target‑dependent follow‑ups** (e.g., “any negative reviews on them?”) failing to retrieve cross‑space grounding because the embedding query doesn’t include any pinned‑target evidence.

## Query types (framework contract)
For INFORMATION + retrieval flows we treat queries as:

- **User query**: the (PII‑processed) text the user actually sent this turn.
  - Stored in request/response metadata as `userQuery`.
  - Used as the “question” for generation prompts.
- **Embedding query**: the string used to compute embeddings and run vector search.
  - Stored as `embeddingQuery`.
  - Deterministically composed by the backend (see below).
- **Optimized query** (LLM output): `Intent.optimizedQuery`.
  - Preserved for debug/telemetry as `optimizedQuery`.
  - Not required to be semantic and is **not** the source of truth for embeddings once `embeddingQuery` is set.

## How embedding query composition works
Implemented in `com.ai.infrastructure.intent.orchestration.rag.EmbeddingQueryComposer`.

### Base
We start from the **user query** (PII‑processed) and (optionally) append the safe `retrievalQueryHint` (ADR‑0009 behavior).

### Optional target hint (mode‑gated)
If all of the following are true:
- `ai.orchestration.rag.target-hint.enabled=true` (or a mode override enables it)
- the intent has `requiresTargetResolution=true`
- `PipelineContext.resolvedTargets` is non‑empty

…then we append a **bounded** semantic hint built from pinned targets:
```
Targets: [vectorSpace=product id=30 sku=SKU-... text="Bose Pro Headphones"]
```

Important properties:
- bounded by `max-targets`, `max-chars`, and `max-content-text-chars-per-target`
- single-line (newlines collapsed)
- metadata keys are **explicitly allowlisted**
  - Allowlist values are read from `ResolvedTarget.metadata` by default.
  - A small set of **reserved root keys** may also be used when allowlisted (domain-agnostic):
    - `id`
    - `vectorSpace` / `type`
    - `text` / `content` / `contentText`

## Configuration

### Global config (safe-by-default)
```yaml
ai:
  orchestration:
    rag:
      target-hint:
        enabled: false
        max-targets: 3
        max-chars: 500
        include-vector-space: true
        include-id: true
        include-content-text: true
        max-content-text-chars-per-target: 120
        metadata-keys-allowlist: []
```

### Mode override (recommended)
Enable only in a “deep” mode:
```yaml
ai:
  orchestration:
    modes:
      navigator_deep:
        rag-target-hint-enabled: true
```

### Metadata allowlist (explicit domain decision)
By default, arbitrary metadata is not appended. For ecommerce you may choose:
```yaml
ai:
  orchestration:
    rag:
      target-hint:
        metadata-keys-allowlist:
          - sku
          - name
          - title
          - category
          - brand
```

## Observability (debug fields)
When enabled, the orchestrator exposes:
- `userQuery`
- `embeddingQuery`
- `targetHintEnabled`
- `targetHintApplied`
- `targetHintTargetsUsed`
- `targetHintChars`

These appear in the INFORMATION result’s `data.metadata` and flow through to `ragResponse.metadata` (the RAG module also records `embeddingQuery`).

## How to validate (manual)
1) Pin two products (UI attachments / pinned targets).
2) Ask: “any negative reviews on them?”
3) Confirm:
   - `ragResponse.metadata.embeddingQuery` includes `Targets: [...]`
   - RAG returns review documents for those products more reliably than without the hint.
