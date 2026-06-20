# Configuration & Optimization Guide (External Users)

This guide is for application teams integrating the AI Fabric Framework (the `ai-infrastructure-module`) and explains the **configuration knobs** that control reliability, cost, and performance for:
- Intent extraction (progressive fallback)
- Multi-domain vector space routing (bounded fan-out + clarification)
- Relationship query planning (LLM → structured plan)

If you are looking for why these choices were made, see:
- `../architecture/adrs/ADR-0001-Externalize-Prompt-Templates.md`
- `../architecture/adrs/ADR-0006-Progressive-Intent-Extraction-Default-On.md`
- `../architecture/adrs/ADR-0007-Relationship-Query-Plan-Repair-And-Structured-Failures.md`

Companion user guides:
- `../actions-governance/GOVERNANCE_AND_COMPLIANCE_GUIDE.md`
- `../testing-verification/REALAPI_PROVIDER_MATRIX_TESTING_GUIDE.md`

---

## 1) Progressive Intent Extraction (Reliability + Cost Control)

**Feature:** progressive intent extraction ladder:
`compound → (optional structural repair) → (optional multi-step fallback)`

**When to enable:**
- Multiple providers in production (different structured-output reliability)
- RealAPI flakiness due to occasional malformed JSON
- You want bounded retries and deterministic fallbacks instead of unbounded loops

### Configuration (`ai.intent-extraction.progressive.*`)

```yaml
ai:
  intent-extraction:
    progressive:
      enabled: true
      repair-enabled: true
      repair-max-attempts: 1
      multi-step-enabled: true
      max-total-llm-calls: 5
      # force-mode: auto | compound | repair | multi_step
```

**Key properties**
- `enabled`: turns on the progressive engine (otherwise the legacy extractor path runs).
- `repair-enabled`: allows a **structural-only** repair attempt when JSON is invalid.
- `repair-max-attempts`: bound for repair attempts.
- `multi-step-enabled`: allows decomposed extraction if compound+repair fail.
- `max-total-llm-calls`: hard budget for extraction per request (cost guardrail).
- `force-mode`: debugging tool; do not keep set in production unless intentionally forcing behavior.

**Operational tip:** monitor request-level diagnostics (`llmCalls`, attempts, extractionPath) to see how often repair/multi-step is used and tune `max-total-llm-calls` accordingly.

---

## 2) Vector Space Routing (Multi-Domain RAG Optimization)

When intent extraction omits `vectorSpace` (or provides multiple candidate spaces), the pipeline can route in a deterministic and bounded way.

### Configuration (`ai.rag.vectorspace-routing.*`)

```yaml
ai:
  rag:
    vectorspace-routing:
      strategy: BOUNDED_FAN_OUT   # HEURISTIC | BOUNDED_FAN_OUT | CLARIFICATION
      fan-out-max-spaces: 3
      fan-out-top-k-per-space: 5
      fan-out-rag-threshold: 0.3
      clarification-threshold: 0.4
```

**Key properties**
- `strategy`
  - `BOUNDED_FAN_OUT`: query multiple spaces, merge/rerank deterministically, ask clarification if weak.
  - `HEURISTIC`: pick a single space deterministically (lowest cost; can misroute in multi-domain KBs).
  - `CLARIFICATION`: always ask a question when routing is ambiguous.
- `fan-out-max-spaces`: bounds fan-out breadth (cost + latency control).
- `fan-out-top-k-per-space`: bounds documents fetched per space.
- `fan-out-rag-threshold`: **per-space retrieval threshold** passed into each `RAGRequest.threshold`.
  - Increase to reduce low-quality matches and reduce context size.
  - Decrease if you’re seeing false “clarification required” due to sparse embeddings.
- `clarification-threshold`: if best merged score is below this, the pipeline asks user to clarify the domain.

**Tuning guidance**
- If you prioritize **precision**: increase `fan-out-rag-threshold` (e.g., `0.35–0.55`) and/or increase `clarification-threshold`.
- If you prioritize **recall**: decrease `fan-out-rag-threshold` (e.g., `0.0–0.25`) and/or decrease `clarification-threshold`.
- If you prioritize **latency/cost**: reduce `fan-out-max-spaces` and `fan-out-top-k-per-space`.

---

## 3) Relationship Query Planning (Structured Plan Reliability)

The relationship query module uses the LLM to create a structured plan (filters, paths, strategy). Some providers can truncate larger JSON plans unless token limits are sufficient.

### Configuration (`ai.infrastructure.relationship.*`)

```yaml
ai:
  infrastructure:
    relationship:
      enabled: true
      enable-vector-search: true
      fallback-to-metadata: true
      fallback-to-vector-search: true
      fallback-to-simple-search: true

      llm:
        model: ""          # empty = framework/provider default
        temperature: 0.1
        max-tokens: 2000   # increase if you see truncation / MAX_TOKENS
        min-confidence: 0.6

      planner:
        max-retries: 0          # bounded retries on invalid plans
        fail-on-parse-error: false
        min-confidence-to-execute: 0.55
```

**Key properties**
- `llm.max-tokens`: primary mitigation for truncated JSON plans.
- `planner.max-retries`: retry when the plan fails deterministic validation.
- `planner.fail-on-parse-error`: set `true` if you prefer fail-closed instead of degraded fallback plans.
- `enable-vector-search`: allows the module to use vector reranking when the LLM indicates it’s needed.

`ReliableRelationshipQueryService` is the public safe boundary for application and action callers:
blank queries and execution failures return `RAGResponse.success=false` with `metadata.errorCode`.
Structured planner failures preserve execution stage, candidate entity types, fallback status, and
provider-specific error attributes in response metadata.

---

## 4) Prompt Template Externalization (Planned)

The multi-step intent extraction prompts are currently embedded in code for correctness, but externalization is recommended for faster tuning and safer rollout.

Planned work is tracked here:
- `changes/PROMPT_TEMPLATES_EXTERNALIZATION_CHANGE_PLAN.md`
