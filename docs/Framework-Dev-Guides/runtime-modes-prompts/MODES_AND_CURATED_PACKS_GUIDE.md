# Modes + Curated Packs (v1 Guide)

This guide explains how to enable and use **modes** via **curated packs**, with a focus on the v1 behavior implemented in:
- Phase 0 (policy surface + capability bundles)
- Phase 1 (`navigator_deep` retrieval semantics)
- Phase 2 (`executor` tightening)

Related planning:
- `../architecture/actions-governance/PLAN_READ_ONLY_ACTION_RESOLVER_AND_THINKER_MODES.md`

It is intentionally practical: **what to set**, **what to expect**, and **how to debug**.

---

## 1) The mental model

### Mode = “capability bundle” + “RAG budgets”
Per request, the server resolves an `OrchestrationPolicy` with:
- capabilities:
  - `actionsEnabled`
  - `retrievalEnabled`
  - `deepRetrievalEnabled`
  - `suggestionsEnabled`
  - `exposeReadProbeFallbackAttempt` (optional debug visibility for READ→RAG fallback)
- rag budgets:
  - `fanoutEnabled`
  - `maxSpaces`
  - `topKPerSpace`
  - `maxDocumentsReturnedToClient`
  - `maxDocumentsUsedForContext`
  - `maxContextChars`
  - `retrievalVectorSpacesAllowlist`

The pipeline is the authority:
- The LLM can *propose* intents.
- The server policy decides what is *allowed* (fail‑closed).

### Curated pack = “config + prompts”
Curated packs ship:
- a pack YAML (`ai-curated/packs/<pack>.yml`)
- optional prompt overlays (prompt templates)

They do not replace pipeline logic.

---

## 2) How the effective mode is chosen

Request inputs (from `OrchestrationContext`):
- `mode` (optional selector key; application-defined string)
- `position` (optional UI hint; application-defined string; **not used by core for routing**)

Resolution order:
1) If request has `mode` and it exists under `ai.orchestration.modes.*` → requested mode wins.
2) Otherwise, `ai.orchestration.default-mode` is used (must also exist under `ai.orchestration.modes.*`).

Notes:
- Core does **not** route based on `position`. If you want “position → mode”, implement it in your app/web layer as an optional router (curated packs may ship an advisory `position-routing` map, but core ignores it).

---

## 3) Modes shipped in the `commerce` pack (Real App default)

The `commerce` curated pack (`ai-curated-commerce`) configures:

### `navigator` (unchanged)
Use for:
- product discovery / semantic search
- comparisons / summaries grounded in KB + (optional) attachments

Characteristics:
- actions enabled
- retrieval enabled
- deterministic “RAG + generate” behavior (when profile defaults to it)

### `navigator_deep` (deep retrieval)
Use for:
- “go deep” queries that need broader coverage:
  - alternatives
  - reviews / complaints
  - policies

Characteristics:
- actions disabled (fail‑closed if LLM proposes actions)
- retrieval enabled
- deep retrieval enabled (fanout allowed when configured)
- bounded multi‑space retrieval (caps enforced by budgets)

### `executor` (action-first)
Use for:
- orders / returns / refunds / subscriptions
- “do X” operational flows

Characteristics:
- action catalog is included in prompting
- KB overview is suppressed to avoid “browsing” confusion
- retrieval is optional, but if enabled it is **restricted**:
  - requires `retrievalVectorSpacesAllowlist` to be configured
  - requires `vectorSpace` to be explicitly set by the LLM when `requiresRetrieval=true`

### `cart_assistant`
Use for:
- cart-specific action flows (add to cart / checkout helpers)

---

## 4) Key configuration (app-level)

### Enable a curated pack
Example:

```yaml
ai:
  curated:
    pack: commerce
```

Maven:

```xml
<dependency>
  <groupId>com.ai.fabric</groupId>
  <artifactId>ai-curated-commerce</artifactId>
  <version>1.0.0</version>
</dependency>
```

### Override a mode
Because pack YAML is loaded with low precedence, your app can override:

```yaml
ai:
  orchestration:
    modes:
      navigator_deep:
        rag:
          max-spaces: 4
```

---

## 5) Executor “restricted retrieval” (important)

If you enable retrieval in an “action-first” mode and require an allowlist, you must provide it:

```yaml
ai:
  orchestration:
    modes:
      executor:
        retrieval-enabled: true
        retrieval-allowlist-required: true
        rag:
          retrieval-vector-spaces-allowlist:
            - policy
```

Behavior:
- If allowlist is missing/empty and the LLM requests retrieval → `CLARIFICATION_REQUIRED` with `reason=RETRIEVAL_ALLOWLIST_REQUIRED`.
- If allowlist exists but the LLM omits `vectorSpace` (and policy requires explicit selection) → `CLARIFICATION_REQUIRED` with:
  - `reason=VECTOR_SPACE_REQUIRED_BY_POLICY`
  - `allowedVectorSpaces=[...]`
- If the LLM requests a denied space → `CLARIFICATION_REQUIRED` with `reason=VECTOR_SPACE_NOT_ALLOWED_BY_POLICY`.

---

## 6) Debug fields to look at (UI / logs)

In the orchestrator response `metadata.orchestrationPolicy`:
- `profile`
- `mode`
- `position`
- `modeSource` (`REQUEST_MODE` vs `DEFAULT_MODE`)
- `actionsEnabled`, `retrievalEnabled`, `deepRetrievalEnabled`, `suggestionsEnabled`
- `actionsPreferred`, `knowledgeBaseOverviewEnabled`, `retrievalAllowlistRequired`, `vectorSpaceSelectionRequired`
- `exposeReadProbeFallbackAttempt` (when true, READ→RAG fallback attempts may be surfaced)

In the orchestrator response `result.metadata.readProbe` (only when enabled by policy):
- Present when a READ action returned an empty successful payload and the orchestrator fell back to RAG.
- Contains a structured summary of the attempted READ action (no silent replacement).

In `result.data.ragResponse.metadata` (when retrieval runs):
- `embeddingQuery`
- `optimizedQuery`
- fanout selection information (when multi‑space)

---

## 7) Real App usage (chat-capabilities-demo)

The demo app already accepts:
- `position`
- `mode`

Examples live in:
- `Real_Apps/chat-capabilities-demo/requests/demo.http`

Recommended patterns:
- normal browsing: send `position=landing|catalog|search`
- deep search: send `mode=navigator_deep`
- action flows: send `mode=executor`

If you still want the older “position → mode” convenience, implement it in the demo app (or your app) as a pre-orchestration router that sets `mode` when it is missing.
