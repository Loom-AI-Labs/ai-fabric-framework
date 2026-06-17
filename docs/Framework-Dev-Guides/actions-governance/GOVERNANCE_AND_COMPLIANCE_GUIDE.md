# Governance & Compliance Guide (External Users)

This guide explains how to enable and operate the **Governance module** (`ai-infrastructure-governance`) for:
- retention cleanup
- GDPR/CCPA deletion orchestration
- compliance checks + content filtering
- optional SQL catalog for enumeration/audit-style inventory
- governance-level coordination of PII detection (without owning PII implementation)

Related ADRs:
- `../architecture/adrs/ADR-0003-Introduce-Optional-Governance-Module.md`
- `../architecture/adrs/ADR-0004-IndexCatalog-Modes-And-Stable-Timestamps.md`
- `../architecture/adrs/ADR-0005-SQL-Catalog-Consistency-Semantics.md`

---

## 1) Enable Governance

Add the module dependency:
- Maven: `com.ai.fabric:ai-infrastructure-governance`

Enable in configuration:

```yaml
ai:
  governance:
    enabled: true
```

When disabled (default), governance beans are not created and there is no behavior change.

---

## 2) Index Catalog (Inventory) Modes

Governance features that need **enumeration** (retention cleanup, deletion discovery) use `IndexCatalog`.

Configuration:

```yaml
ai:
  governance:
    enabled: true
    catalog:
      mode: AUTO  # AUTO | VECTOR | SQL | DISABLED
```

### `AUTO` (recommended)

`AUTO` chooses:
- `VECTOR` if the configured `VectorDatabaseService` supports *scan + metadata filtering*
- else `SQL` if JPA repository support is present
- else `DISABLED`

### `VECTOR`

Derives catalog inventory directly from vector DB via provider scan/filter APIs.

### `SQL`

Persists a minimal catalog (entityType/entityId/vectorId + timestamps + metadata JSON) for enumeration.

Important consistency semantics (read before choosing SQL):
- `../architecture/adrs/ADR-0005-SQL-Catalog-Consistency-Semantics.md`

### `DISABLED`

Disables catalog-driven governance workflows. Features requiring enumeration will not run.

---

## 3) Stable Index Timestamps

Governance standardizes retention timing via vector metadata keys:
- `_indexedCreatedAt`
- `_indexedUpdatedAt`

These are injected/updated automatically when governance is enabled.

---

## 4) Retention Cleanup

Enable:

```yaml
ai:
  governance:
    enabled: true
    retention:
      enabled: true
      cron: "0 30 3 * * *"
      entity-types: [ "product", "document" ]
      retention-days:
        product: 30
        document: 90
      scan-limit: 200
```

Notes:
- Retention cleanup enumerates via `IndexCatalog.scan(...)`.
- Deletions are performed via `VectorDatabaseService.removeVector(entityType, entityId)`.
- For custom per-entry logic, provide a `RetentionPolicyProvider` bean.

---

## 5) GDPR/CCPA Deletion Orchestration

Enable:

```yaml
ai:
  governance:
    enabled: true
    deletion:
      enabled: true
```

Requirements:
- Provide a `UserDataDeletionProvider` bean (policy + domain deletions).

The governance deletion service uses `IndexCatalog` to discover indexed items when the application cannot enumerate them.

---

## 6) Compliance Checks + Content Filtering

Enable compliance:

```yaml
ai:
  governance:
    enabled: true
    compliance:
      enabled: true
```

Requirements:
- Provide a `ComplianceCheckProvider` bean.

Enable content filtering:

```yaml
ai:
  governance:
    enabled: true
    content-filter:
      enabled: true
```

---

## 7) PII Under Governance (Coordination Only)

Governance can “own the toggle” by validating that PII capabilities are present when governance PII is enabled.

Enable governance PII coordination:

```yaml
ai:
  governance:
    enabled: true
    pii:
      enabled: true
      require-detection-service: true
      require-pipeline-step: false
```

PII detection itself remains enabled via the PII module configuration:

```yaml
ai:
  pii-detection:
    enabled: true
    mode: REDACT
```

---

## 8) Operational Guidance (What to Expect)

- Prefer `catalog.mode=AUTO` unless you have a reason to force a specific mode.
- Prefer `VECTOR` mode when your vector vendor supports scan/filter server-side.
- Use `SQL` mode when you need governance enumeration but your vector DB cannot provide scan/filter in a reliable way.
- For `SQL` mode, read and align on the consistency semantics in:
  - `../architecture/adrs/ADR-0005-SQL-Catalog-Consistency-Semantics.md`

