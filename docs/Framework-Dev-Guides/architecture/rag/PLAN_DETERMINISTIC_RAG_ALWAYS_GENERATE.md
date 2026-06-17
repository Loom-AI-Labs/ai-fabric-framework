# Plan: Deterministic RAG + Always-Generate for INFORMATION (Feature-Flagged)

## Goal
Make the framework (and specifically `Real_Apps/chat-capabilities-demo`) far less sensitive to LLM “contract” mistakes by enforcing a deterministic policy:

> If `intent.type = INFORMATION` → **always retrieve (RAG)** → **always generate a response using retrieved context**.

This removes the common failure mode where the LLM fails to set `requiresGeneration=true`, resulting in retrieval-only behavior (e.g., `"Search completed."`) or misroutes/omits `vectorSpace` causing no/low-quality retrieval.

## Non-goals
- Do not “tune” the framework with prompt tweaks as the primary mechanism.
- Do not change default behavior for existing apps unless they explicitly enable a feature flag.
- Do not remove advanced/conditional modes; we only add a deterministic mode alongside them.

## Current pain points (observed)
1. **Generation often does not happen** because `needsGeneration = intent.requiresGenerationOrDefault(false)`; if the model omits it, the system returns a retrieval-only message.
2. **Retrieval can be blocked** when `vectorSpace` is missing/empty (leading to clarification paths) or low-confidence routing.
3. Prompt asks the model to decide too many fields (`requiresGeneration`, `requiresRetrieval`, `vectorSpace`, `needsAdvancedRAG`, etc.), increasing the chance of invalid/undesirable outputs (OUT_OF_SCOPE / ERROR).

## Proposed solution overview
Introduce a feature-flagged **Information Handling Mode** that overrides the LLM-controlled “requiresGeneration / requiresRetrieval” decisions.

### New feature flags (suggested)
Add new configuration keys (names can be adjusted to match existing style):

```yaml
ai:
  orchestration:
    information-mode: DETERMINISTIC_RAG_GENERATE   # default: LLM_DRIVEN (current)
  intent-extraction:
    prompt-mode: MINIMAL_FOR_RAG                   # default: FULL_CONTRACT (current)
```

Where:
- `information-mode=LLM_DRIVEN` preserves current behavior.
- `information-mode=DETERMINISTIC_RAG_GENERATE` forces retrieval + generation for INFORMATION.
- `prompt-mode=FULL_CONTRACT` uses the existing schema-heavy prompt.
- `prompt-mode=MINIMAL_FOR_RAG` uses a simplified schema where the LLM is not asked to set `requiresGeneration/requiresRetrieval`.

## Detailed design

### 1) Prompt structure (feature-flagged)
Add a prompt “mode switch” so we can keep the existing prompt for framework users while offering a simpler contract for apps that want deterministic RAG.

#### Prompt mode: `MINIMAL_FOR_RAG`
Design principles:
- LLM only decides: **ACTION vs INFORMATION vs OUT_OF_SCOPE**, **action + params**, and **optimizedQuery**.
- `requiresGeneration` and `requiresRetrieval` are removed from the schema (or ignored if present).
- `vectorSpace` is optional (best-effort). If it is omitted, routing is handled deterministically (see below).

Example minimal schema:
```json
{
  "intents": [
    {
      "type": "ACTION | INFORMATION | OUT_OF_SCOPE | COMPOUND",
      "intent": "canonical_intent_name",
      "confidence": 0.0,
      "action": "action_name_if_applicable",
      "actionParams": {"key": "value"},
      "vectorSpace": "optional_entity_type_or_domain",
      "optimizedQuery": "required_for_INFORMATION"
    }
  ],
  "isCompound": false,
  "orchestrationStrategy": "DIRECT_ACTION | RETRIEVE_AND_GENERATE | ADMIT_UNKNOWN",
  "metadata": {}
}
```

Rules specific to this mode:
- If the user asks for *any* informational answer (recommendations, “I need a laptop…”, “find products…”, “compare…”) → `type=INFORMATION`.
- For INFORMATION: always provide `optimizedQuery` (rewrite using known entity type names / field hints when available).
- Use `OUT_OF_SCOPE` only for truly unrelated requests or unsupported state-changing actions.

### 2) Orchestration policy for INFORMATION (feature-flagged)
When `ai.orchestration.information-mode=DETERMINISTIC_RAG_GENERATE`:

**Hard rules**
- Treat every `IntentType.INFORMATION` as:
  - `requiresRetrieval = true`
  - `requiresGeneration = true`
- Always run RAG first, then run generation using `generateRagAnswer(query, ragContext)`.

**Query selection**
- Use `intent.optimizedQuery` if present; else fallback to the pipeline effective query; else the original user query.

**Vector-space selection**
- If `intent.vectorSpace` is set:
  - If it contains multiple spaces (comma-separated) → run fan-out RAG and merge results.
  - Else run basic RAG against that single space.
- If `intent.vectorSpace` is missing/blank:
  - Prefer deterministic routing:
    1) If the knowledge base overview has entity types (e.g., `product`) → run bounded fan-out across them.
    2) If only one type exists → use it directly (no clarification).
    3) If no types exist → still generate a response (with “no indexed data” fallback).

**RAG thresholds**
Make thresholds configurable for this mode (the current defaults like `DEFAULT_RAG_THRESHOLD=0.6` can be too strict for small corpora):
```yaml
ai:
  rag:
    thresholds:
      information: 0.3
      fan-out: 0.3
```

**No-docs behavior**
If retrieval returns 0 documents:
- Still generate a response, but explicitly explain that the catalog/knowledge base has no matching entries and propose next steps (e.g., ask user to add products or refine criteria).

### 3) Handling OUT_OF_SCOPE in this mode (optional but recommended)
Many “OUT_OF_SCOPE” results are caused by the model being conservative with the current prompt.
In deterministic mode, add a safe fallback:

- If intent is `OUT_OF_SCOPE` but **no action** is requested and the query looks informational → coerce to INFORMATION and run the deterministic RAG+Generate flow.

This keeps safety: we are not executing any state-changing action; we are only searching indexed content and summarizing.

### 4) API response simplification (optional)
Even with better results, the response payload is large. Add an optional `?compact=true` mode to return only:
- `result.sanitizedPayload` (plus maybe top-k docs if the UI needs them).

This is independent of the deterministic intent strategy, but improves UX and reduces confusion.

## Rollout plan
1. Add new configuration properties + enums for `information-mode` and `prompt-mode`.
2. Implement prompt mode switching in the prompt builder layer.
3. Implement deterministic INFORMATION flow in orchestration (behind the flag).
4. Enable the flags in `Real_Apps/chat-capabilities-demo` by default for the demo environment.
5. Add tests:
   - Unit test: INFORMATION intent always generates in deterministic mode.
   - Unit test: missing vectorSpace triggers fan-out across known entity types.
   - Integration test: “I need gaming laptop” returns `INFORMATION_PROVIDED` with a generated answer when products are indexed.

## Acceptance criteria
- For common catalog queries (e.g., “i need gaming laptop”, “laptop for programming”), the API returns:
  - `result.type = INFORMATION_PROVIDED`
  - `result.message` contains a natural-language answer (not “Search completed.”)
  - `result.data.documents` includes retrieved product documents when available
- If vectorSpace is missing, retrieval still happens (fan-out or default type) and does not stop with “Which domain should I search?”
- OUT_OF_SCOPE becomes rare for informational queries; ERROR becomes rare outside provider outages.

## Risks / trade-offs
- Always generating increases LLM cost and latency.
- Deterministic fan-out across many entity types can increase retrieval cost; keep it bounded (`fanOutMaxSpaces`) and rely on KB overview counts.
- Some apps genuinely want retrieval-only. That remains available via default mode.

