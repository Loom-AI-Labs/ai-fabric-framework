# ADR-0003: Introduce Optional Governance Module (Keep Core Clean)

## Status
Accepted

## Context
Enterprise/governance concerns (retention, deletion discovery, compliance checks, PII coordination) add complexity that many users do not need.

The framework goal is to keep `ai-fabric-core` minimal and composable, while still supporting paid/enterprise features without forking the architecture.

## Decision
Introduce `ai-infrastructure-governance` as an **optional module** that is enabled only when:

```yaml
ai:
  governance:
    enabled: true
```

Governance integrates via:
- `VectorDatabaseService` decorator/wrapper (transparent to the application)
- `IndexCatalog` abstraction for enumeration (vector-native or SQL-backed)
- opt-in services/steps for compliance and content filtering
- validation hooks for governance-level PII requirements without owning PII implementation.

## Consequences
### Positive
- Clean separation of concerns: core remains focused on RAG/indexing/search plumbing.
- Governance features can evolve independently (enterprise features do not pollute core API).
- Works across vector vendors via capability detection and catalog modes.

### Negative / Trade-offs
- Users must understand module boundaries (governance “enabled” does not automatically enable PII detection itself).
- Governance SQL catalog cannot be truly atomic with remote vector DB writes (addressed in ADR-0005).

## Alternatives Considered
1. **Keep governance in core**
   - Faster initially, but permanently bloats the core and complicates adoption.
2. **Separate “enterprise-services” module**
   - Considered, but “governance” is more precise and aligns with retention/compliance semantics (chosen name: `ai-infrastructure-governance`).

## Implementation Notes
- Governance enablement gates beans via `@ConditionalOnProperty(ai.governance.enabled=true)`.
- Compliance and content-filter services are owned by governance to preserve a “clean core”.
- Governance can enforce configuration contracts (fail fast when governance requires PII beans/steps).

