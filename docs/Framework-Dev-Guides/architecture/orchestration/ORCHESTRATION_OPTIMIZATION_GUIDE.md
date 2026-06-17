# Orchestration Optimization Guide (Profiles, Modes, Deterministic RAG, Pinned Targets)

**Document Purpose:** Explain how to configure and reason about orchestration “optimizations” (profiles/modes), with a focus on reducing unnecessary RAG while keeping answers grounded and stable across turns.

This guide complements:
- `../rag/PLAN_DETERMINISTIC_RAG_ALWAYS_GENERATE.md`
- `../actions-governance/PLAN_READ_ONLY_ACTION_RESOLVER_AND_THINKER_MODES.md`
- `../../runtime-modes-prompts/CURATED_MODES_PACKS_GUIDE.md`
- `./NORMALIZATION_AND_ORCHESTRATION_GUIDE.md`

---

## 1) Terms (shared vocabulary)

### Profile (`ai.orchestration.profile`)
A **server-side preset** that chooses coherent defaults for orchestration.

Current profiles:
- `DEFAULT` → LLM-driven information routing (baseline).
- `PRODUCTION_NAVIGATOR` → deterministic navigation/catalog behavior (reliable, retrieval-first).
- `PRODUCTION_CHAT` → conversational assistant behavior (allows “no-retrieval” short replies when appropriate).

Profiles are designed for **one-setting enablement** and reproducibility.

### Mode (`ai.orchestration.modes.*`)
An allowlisted **bundle of overrides** (selected by the server using the resolved `mode`).

The request may provide `mode` explicitly; otherwise the server uses `ai.orchestration.default-mode`.

Example modes (app-defined):
- `navigator`
- `cart_assistant`
- `support_resolver`

Mode capabilities can also control action-answering behavior. For example, thinker-style modes can enable:

```yaml
ai:
  orchestration:
    modes:
      thinker:
        force-grounding-eligible-read-action-post-generation: true
```

When enabled, direct READ actions marked `groundingEligible=true` are converted into a generated, user-facing answer from the action result instead of returning action output as the primary message. Leave this disabled for modes that should preserve action-first/raw-result behavior.

### Position routing (`ai.orchestration.position-routing`) (advisory)
Maps a UI/system “position” to a mode:
- `landing → navigator`
- `cart → cart_assistant`
- `support → support_resolver`

Core does **not** route based on `position`. If you want “position → mode” convenience, implement it **outside core** (app/web layer) as a pre-orchestration router that sets `mode` when it is missing.

Rationale:
- Keeps core position-agnostic and domain/UI-agnostic.
- Makes behavior predictable: only `mode` + policy capability flags drive execution.

### Information mode (`LLM_DRIVEN` vs `DETERMINISTIC_RAG_GENERATE`)
This controls how INFORMATION intents are executed:
- `LLM_DRIVEN` → the extractor decides `requiresRetrieval` / `requiresGeneration`.
- `DETERMINISTIC_RAG_GENERATE` → INFORMATION always retrieves (RAG) and generates.

### Pinned targets / resolved targets
**Pinned targets** are the *authoritative* user-selected or system-carried entities/documents the user is referring to (e.g. active attachments).

In code they are represented as `resolvedTargets` (authoritative context), not to be confused with “working set” docs.

### Working set (retrieved docs)
The **working set** is the documents retrieved *this turn* from the vector DB.

Working set docs are helpful, but not authoritative:
- they can change from turn to turn,
- they can drift if the query is ambiguous,
- they should never override explicit user attachments.

---

## 2) One-setting enablement (recommended)

### Option A — Curated pack (best OSS UX)
Use a curated pack to enable “a coherent set” (profile + modes + prompt overlays):

```yaml
ai:
  curated:
    pack: commerce
```

Curated packs:
- ship only **defaults** and **prompt templates** (no hidden pipeline logic),
- can be overridden by the application config deterministically.

### Option B — Manual configuration
For a navigator-first production app:

```yaml
ai:
  orchestration:
    profile: PRODUCTION_NAVIGATOR
    modes:
      navigator:
        information-mode: DETERMINISTIC_RAG_GENERATE
    default-mode: navigator
```

---

## 3) “Less RAG” without losing grounding

### Core rule
**RAG is not a goal. Grounded answers are the goal.**

The system should call the vector DB when (and only when) it needs additional data beyond the user’s current authoritative context.

### The authoritative-first hierarchy (must be explicit)
When answering a user turn:

1) **Active attachments / explicit UI selection** → authoritative pinned targets.
2) **Stored pinned targets** (short window, if enabled) → authoritative.
3) **Working set (retrieved docs)** → helpful but non-authoritative.

This prevents drift and makes follow-ups (“compare them”, “summarize it”, “buy it”) stable.

### Deterministic mode optimization (important)
In `DETERMINISTIC_RAG_GENERATE`, INFORMATION normally always retrieves.

However, we can still reduce unnecessary retrieval *safely*:
- If the request is **target-dependent** (about a specific item) and **pinned targets are present**, the system can answer from pinned targets and skip vector DB retrieval.

This optimization is safe because the user already provided the authoritative context.

---

## 4) Should pinned context be included in intent extraction?

### Yes — when you ask the LLM to make “enough context?” decisions
It is good practice to include **bounded, authoritative pinned context** (attachments / resolved targets) in intent extraction when the extraction prompt asks the LLM to decide:
- `requiresRetrieval` (do we need more data?)
- `requiresTargetResolution` (is the query about “this/it/them”?)
- action selection/parameter filling that depends on the active item (e.g., “buy it”)

If the extractor only sees the raw user query, it cannot reliably answer:
> “Do you have enough information to answer without retrieval?”

Because it doesn’t know what the UI already pinned.

### What to include (best practice)
Include **only** what is needed to ground intent decisions:
- `id`, `vectorSpace`, and a bounded `contentText`
- bounded scalar metadata (e.g., `sku`, `category`, `price`, `currency`)
- avoid large text blocks, avoid long history, enforce strict token/size budgets

### What not to include
- full chat history (belongs in a separate “history” section, with windowing)
- entire working-set doc dumps (too large, increases confusion)
- unbounded metadata (risk: prompt injection, token explosion)

### Why this matches the framework philosophy
From `../framework/AI_FABRIC_FRAMEWORK_PHILOSOPHY.md`:
- **Respecting intelligence:** don’t ask the LLM to decide without giving it the relevant system facts.
- **Performance as a feature:** keep the context bounded and structured.
- **Security is not optional:** apply sanitization/PII rules before prompts.

---

## 5) “Why do we ask the LLM if it has enough info, but we don’t provide context?”

That mismatch is a real failure mode:
- The model defaults to `requiresRetrieval=true` because it cannot know what’s pinned/available.
- The pipeline ends up doing unnecessary vector DB calls (cost/latency), and can drift if the query is ambiguous.

There are only two correct solutions:

1) **Provide the relevant authoritative context** to the extractor (bounded pinned targets), so it can set `requiresRetrieval=false` when appropriate.
2) **Do not rely on the LLM for that decision**; enforce a deterministic policy in code based on known system state (e.g., “if pinned targets exist and the request is target-dependent, skip retrieval”).

The framework supports using both:
- LLM-driven mode benefits strongly from (1),
- deterministic navigator mode benefits strongly from (2) to reduce unnecessary retrieval while staying grounded.

---

## 6) Deep search (Advanced RAG) in production

Advanced RAG is higher cost/latency. Treat it as:
- **off by default**, and
- **user-triggered or explicitly routed** (via mode, or request metadata).

Recommended options:
- Add a “deep search” button in the UI that sets a request flag (or selects a `navigator_deep` mode).
- Keep auto-enable for “complex queries” disabled unless you have strong governance and budgets.

---

## 7) Observability (how you verify behavior)

For debugging and realapi tests, the API should expose (in debug metadata):
- effective profile/mode/position
- whether retrieval was skipped and why (e.g., `PINNED_TARGETS`)
- vector spaces selected (and whether selected by router vs explicit intent)

This makes behavior reproducible and testable without log scraping.
