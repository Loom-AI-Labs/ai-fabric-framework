# ADR-0005: SQL Catalog Consistency Semantics (Retry + Fail, No Distributed TX)

## Status
Accepted

## Context
When governance catalog mode is `SQL`, the system updates:
1. a vector database (often remote)
2. a local relational catalog (`JpaIndexCatalog`)

These systems do not share a transaction coordinator. True atomicity (2PC) is not available and would add heavy complexity.

## Decision
In `SQL` catalog mode:
- perform bounded retries for transient SQL catalog failures (upsert/delete)
- if the catalog write still fails, **propagate the failure** (treat the vector lifecycle call as failed)

Rationale:
- indexing flows are idempotent and already designed for retry/backoff (queue-based indexing)
- failing fast is safer than silently drifting a governance catalog used for retention/deletion enumeration.

## Consequences
### Positive
- Prevents silent catalog drift in governance-critical flows.
- Encourages convergence via upstream retries rather than hidden inconsistency.
- Keeps complexity low (no distributed transaction layer).

### Negative / Trade-offs
- It is still possible for the vector DB write to have succeeded before the catalog failure (remote side-effects).
- A failure may cause a transient “already stored” state; operations must remain idempotent.

## Alternatives Considered
1. **Swallow catalog failures (best-effort)**
   - Risks governance drift; retention/deletion may miss data.
2. **Compensating transactions (delete vector if catalog fails)**
   - Not reliable across vendors and failure modes; can delete good vectors on transient SQL issues.
3. **Distributed transactions / 2PC**
   - Too heavy and brittle for a framework supporting many backends.
4. **Retry then fail (chosen)**
   - Works with idempotent indexing and keeps governance integrity as a priority.

## Implementation Notes
- Keep retry bounded (small attempt count + short backoff).
- Log warnings on intermediate failures; emit an error on final failure.
- Document the semantics clearly for users choosing `SQL` mode.

