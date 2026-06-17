# ADR-0007: Relationship Query Plan Repair + Structured Failure Responses

## Status
Accepted

## Context
Relationship query planning relies on structured JSON plans emitted by an LLM. Real providers can return:
- truncated JSON (token limits)
- malformed JSON
- partial plans that still contain salvageable intent.

Failing with opaque 5xx responses reduces usability and makes integration tests flaky.

## Decision
When relationship-query plan parsing fails, attempt a bounded “repair” step to recover a valid plan payload, then re-parse and validate it.

On failures that cannot be repaired, prefer returning a **structured error response** (HTTP 200 + `success=false` + `errorMessage` + metadata like `errorStage`) rather than hard crashing.

## Consequences
### Positive
- Improves robustness against provider truncation/malformed JSON.
- Better UX and debuggability: callers can understand why the plan failed.
- RealAPI integration tests are less brittle and assert semantics rather than provider-specific quirks.

### Negative / Trade-offs
- Adds an extra LLM call in certain failure cases (bounded).
- Must ensure repair does not “hallucinate” constraints; validation remains required.

## Alternatives Considered
1. **Fail immediately on parse error**
   - Simple but brittle across real providers.
2. **Silent fallback plan**
   - Risks executing incorrect queries; violates “fail-closed” principles.
3. **Repair then validate, else structured failure (chosen)**
   - Keeps correctness guarded while improving resilience.

## Implementation Notes
- Repair is only used when parsing fails; validation is still enforced after repair.
- Structured failures must include enough metadata for troubleshooting (e.g., `plan` when success, `errorStage` when failure).

