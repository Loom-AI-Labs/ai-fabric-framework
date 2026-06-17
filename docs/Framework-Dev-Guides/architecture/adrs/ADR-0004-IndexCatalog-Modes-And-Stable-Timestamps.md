# ADR-0004: IndexCatalog Modes (AUTO/VECTOR/SQL) + Stable Index Timestamps

## Status
Accepted

## Context
Governance use cases (retention cleanup, deletion discovery, audit-ish inventory) require:
- enumerating indexed items
- paging through large sets (scan)
- filtering by metadata (e.g., userId)
- stable timestamps for retention windows.

Vector DB vendors vary in their support for metadata filtering and paged scans.

## Decision
Introduce `IndexCatalog` with multiple modes:
- `AUTO`: prefer vector-native catalog when provider supports scan + metadata filtering; else fall back to SQL when available; else disabled
- `VECTOR`: derive catalog via vector DB scan/filter
- `SQL`: persist a minimal relational catalog (keyed by entityType/entityId)
- `DISABLED`: no catalog (governance workflows requiring enumeration are unavailable)

Additionally, standardize stable timestamps via vector metadata keys:
- `_indexedCreatedAt`
- `_indexedUpdatedAt`

These are enriched by the governance `VectorDatabaseService` decorator.

## Consequences
### Positive
- Governance can work across vendors without forcing the lowest common denominator.
- Retention timing is stable even when vector DB lacks first-class timestamp fields.
- Catalog abstraction keeps core free of “inventory store” requirements.

### Negative / Trade-offs
- Providers without server-side filtering/scans may require SQL catalog mode to support deletion discovery/retention enumeration.
- Timestamp enrichment requires consistent metadata propagation in providers.

## Alternatives Considered
1. **Single SQL catalog always**
   - Simple, but forces a relational dependency and duplicates metadata for everyone.
2. **Require all vector providers to support scan/filter**
   - Not realistic across vendors and deployment modes.
3. **AUTO/VECTOR/SQL with stable metadata timestamps (chosen)**
   - Lets governance adapt to capabilities while keeping semantics consistent.

## Implementation Notes
- Mode selection depends on `VectorDatabaseService.supportsVectorScan()` and `supportsMetadataFiltering()`.
- Stable timestamps are injected into metadata on store/update and preserved across updates.
- SQL catalog scan uses a cursor based on indexed-updated timestamp for paging.

