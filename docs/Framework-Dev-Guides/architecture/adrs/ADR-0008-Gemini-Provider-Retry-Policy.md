# ADR-0008: Gemini Provider Retry Policy (Reduce Transient 503 Flakiness)

## Status
Accepted

## Context
Gemini API requests may intermittently fail with transient overload/availability errors (e.g., HTTP 503). This caused flaky RealAPI test behavior and non-deterministic user experience.

The framework should handle transient provider failures gracefully without hiding persistent failures.

## Decision
Increase Gemini provider retry attempts and backoff ceiling (bounded retries with exponential backoff) to reduce transient failure flakiness.

## Consequences
### Positive
- Fewer “random” failures for Gemini-backed flows.
- Better resilience under provider load spikes.

### Negative / Trade-offs
- Slightly higher worst-case latency on failing requests (bounded by max attempts/backoff).
- Must ensure retries do not violate cost or rate-limit expectations (caller should still set timeouts appropriately).

## Alternatives Considered
1. **No retries**
   - Fast failures but poor UX under transient provider conditions.
2. **Unbounded retries**
   - Dangerous; can hang and amplify costs.
3. **Bounded retries with backoff (chosen)**
   - Standard resilience pattern with controlled worst-case behavior.

## Implementation Notes
- Keep retries limited and backoff capped.
- Prefer idempotent request patterns; do not retry non-idempotent side effects unless safe.

