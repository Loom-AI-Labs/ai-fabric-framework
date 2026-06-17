# ADR-0006: Progressive Intent Extraction Default ON (Bounded Cost, Deterministic Overrides)

## Status
Accepted

## Context
LLM intent extraction can fail in multiple ways:
- structurally invalid JSON
- partial/truncated outputs
- misclassified intents (e.g., ACTION vs INFORMATION)

In production, this should degrade predictably and be cost-controlled. In tests (especially mocked ones), behavior must remain deterministic.

## Decision
Enable progressive intent extraction by default:
1. **Compound** extraction (primary)
2. **Repair** (structural fix-up when needed)
3. **Multi-step** fallback
4. **Fail-closed fallback** (e.g., OUT_OF_SCOPE) when still unreliable

Add a max-budget (`maxTotalLlmCalls`) to bound LLM usage per request.

For deterministic test suites, disable progressive extraction in `application-test.yml` to avoid provider variability affecting unit tests.

Add deterministic post-processing guardrails:
- if a user explicitly prefixes the query with `relationship_query:` (or variants), force a single ACTION intent for that directive.

## Consequences
### Positive
- Higher reliability across providers without hiding failures.
- Bounded LLM cost for intent extraction.
- Stable “explicit directive” contract independent of provider behavior.

### Negative / Trade-offs
- Slightly more orchestration complexity.
- Some tests must explicitly pin configuration for deterministic behavior.

## Alternatives Considered
1. **Single-pass extraction only**
   - Simpler, but too brittle across real providers.
2. **Always multi-step extraction**
   - More reliable formatting, but worse model utilization and potentially worse intent quality.
3. **Progressive ladder with bounded budget (chosen)**
   - Good reliability/cost trade-off.

## Implementation Notes
- Default enablement is controlled by `ai.intent-extraction.progressive.enabled` (match-if-missing behavior).
- Test profiles explicitly disable progressive extraction for determinism.
- Post-processing must not add extra LLM calls.

