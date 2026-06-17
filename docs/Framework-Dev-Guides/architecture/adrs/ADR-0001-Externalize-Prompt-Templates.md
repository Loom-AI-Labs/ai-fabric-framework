# ADR-0001: Externalize Prompt Templates (Versioned Resources)

## Status
Proposed

## Context
Some modules (notably `MultiStepIntentExtractionStrategy`) embed large prompt strings and JSON schemas directly in Java code. This slows down iteration and makes it harder to:
- review prompt changes independently from logic changes
- run controlled prompt experiments (A/B, provider-tuned prompts)
- version and roll out prompt updates safely

The framework is greenfield and aims to be production-ready and extensible.

## Decision
Adopt **versioned prompt templates stored as classpath resources** and rendered via a small, type-safe core utility:
- Templates live under a stable path such as `prompts/<feature>/<flow>/<version>/`.
- Call sites render templates with explicit placeholder maps and fail fast on missing placeholders.
- Default prompts remain provider-agnostic; optional provider-specific variants are allowed via configuration.

## Consequences
### Positive
- Prompts can be tuned without touching Java source, enabling faster iteration and smaller PR diffs.
- Prompt versions can be pinned and rolled out explicitly via configuration.
- Improves testability: rendered prompts can be validated (required sections, required placeholders).

### Negative / Trade-offs
- Adds resource loading and template rendering surface area (must fail-closed on missing resources/placeholders).
- Introduces operational config for prompt version selection.

## Alternatives Considered
1. **Keep prompts in code**
   - Simple, but couples tuning with code changes and discourages prompt iteration.
2. **Remote prompt registry**
   - Flexible, but introduces network/runtime dependencies and complex rollout concerns.
3. **Spring @ConfigurationProperties for prompts**
   - Useful for small strings, but large multi-line prompts become unwieldy and hard to diff.

## Implementation Notes
- Add prompt rendering utilities to `ai-infrastructure-core` (no network dependency).
- Ensure startup validation for required templates (fail-fast if missing).
- Do not log full prompts at INFO (log length/snippets only) to avoid leaking sensitive schema/context.

