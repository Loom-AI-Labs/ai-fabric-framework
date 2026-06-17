# ADR-0002: Remove AISearchableEntity (Single Source of Truth = Vector DB)

## Status
Accepted

## Context
`AISearchableEntity` duplicated vector content/metadata in a relational store, creating:
- dual-delete complexity (vector DB + SQL)
- synchronization jobs to reconcile drift (orphans, “no-vector” states)
- extra maintenance surface without clear product value when vector DB already stores vectors + metadata.

The framework aims to be greenfield and production-ready, preferring a single source of truth.

## Decision
Remove `AISearchableEntity` and its storage strategies, and treat `VectorDatabaseService` as the single source of truth for indexed content + metadata.

Operational indexing state (retries, dead-letter) remains tracked via the indexing queue (`IndexingQueueEntry`), not via a persistent searchable-entity table.

## Consequences
### Positive
- Eliminates dual-write/dual-delete coordination and sync drift.
- Simplifies compliance flows (deletion/retention operate on one store or a governance catalog derived from it).
- Reduces code and test surface area (legacy patterns removed rather than deprecated).

### Negative / Trade-offs
- Any “SQL audit trail of indexing” requirement must be satisfied via a dedicated governance catalog (see governance ADRs) rather than reintroducing a full searchable-entity store.

## Alternatives Considered
1. **Keep AISearchableEntity as an optional module**
   - Still duplicates data and adds permanent sync complexity.
2. **Keep only mapping (entity → vectorId)**
   - Still introduces dual storage and consistency issues without solving primary use cases.
3. **Rely solely on vector DB + indexing queue (chosen)**
   - Simplest architecture; governance adds opt-in catalog when enumeration/audit is needed.

## Implementation Notes
- Indexing failure tracking is handled by the indexing queue (retry + dead-letter), not by “vectorId is null” rows.
- Governance features that require enumeration rely on `IndexCatalog` (vector-native scan or SQL catalog), not `AISearchableEntity`.

