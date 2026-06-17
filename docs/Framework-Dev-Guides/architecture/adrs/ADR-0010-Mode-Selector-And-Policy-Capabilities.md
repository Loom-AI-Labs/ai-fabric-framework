# ADR-0010 — Mode Is a Selector Key; Capabilities Drive Behavior (No Core Position Routing)

## Status
Accepted

## Context
We want the orchestration core to remain:
- **domain/UI agnostic**
- **predictable** (no hidden routing based on UI concepts)
- **portable** across applications and curated packs

Earlier iterations allowed “position → mode” routing inside core and/or branching on specific mode names (e.g., `executor`, `navigator`).

That created problems:
- **Hidden coupling**: UI concepts (“cart”, “support”) affected behavior inside core.
- **Hard-to-debug**: behavior changed when a client happened to include `position`.
- **Hard-to-productize**: adding a new mode name risked requiring new core logic.

We also want curated packs to remain **transparent**: they should ship configuration defaults, not hidden execution logic.

## Decision
1) Core treats `mode` as an **application-defined selector key**:
   - If request provides `mode` and it exists under `ai.orchestration.modes.*`, it is used.
   - Otherwise, `ai.orchestration.default-mode` is used (must also exist under `ai.orchestration.modes.*`).

2) Core does **not** route by `position`:
   - `position` remains an optional input hint for observability/debug only.
   - Any “position → mode” behavior is implemented **outside core** (app/web layer).

3) Core does **not** branch on specific mode names:
   - Execution is gated by explicit policy capability flags (e.g., `actionsEnabled`, `retrievalAllowlistRequired`), not by string comparisons like `if (mode == "executor")`.
   - Curated packs can define modes with different capabilities, budgets, and prompt constraints using configuration only.

## Consequences
Positive:
- Core stays **stable and reusable** across domains.
- Mode behavior becomes **explicit** (capabilities + budgets), not implicit.
- Curated packs remain transparent configuration overlays.

Negative / tradeoffs:
- Applications that want “position → mode” convenience must add a small router in the app/web layer.
- If a client sends an unknown `mode`, the outcome depends on `strict-mode-routing`:
  - strict → error
  - non-strict → default-mode

## Implementation
- `com.ai.infrastructure.intent.orchestration.pipeline.steps.OrchestrationPolicyResolutionStep`
  - resolves `mode` from request or `default-mode`
  - records `modeSource` (`REQUEST_MODE` / `DEFAULT_MODE`) for debugging
- `com.ai.infrastructure.intent.orchestration.policy.OrchestrationPolicy`
  - defines capability flags (`OrchestrationCapabilities`) that gate behavior
- `com.ai.infrastructure.config.OrchestrationProperties`
  - exposes `modes.*` overrides and capability flags for configuration

