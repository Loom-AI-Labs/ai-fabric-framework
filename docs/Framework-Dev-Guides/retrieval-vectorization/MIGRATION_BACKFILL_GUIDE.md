# Migration Backfill Guide

This guide describes the release-facing behavior of `ai-fabric-migration`, the bulk backfill module
used to enqueue existing application records into the indexing pipeline.

Use migration for one-time or periodic backfills. Use `ai-fabric-data-sync` for ongoing change
propagation after the initial corpus has been indexed.

## Module Shape

| Concern | Implementation |
| --- | --- |
| Job orchestration | `DataMigrationService` |
| Job persistence | `MigrationJobRepository` and `MigrationJob` |
| Progress view | `MigrationProgressTracker` |
| Source repositories | `EntityRepositoryRegistry` using `@AICapable.migrationRepository` |
| Entity projection | `AIEntityDescriptorRegistry` and `AIEntityProjectionService` |
| Filtering | `MigrationFilterPolicy` or `ai.migration.entity-fields.*` |
| Indexing handoff | `AIEntityIndexingGateway` with `IndexingStrategy.ASYNC` |
| Existing-vector skip check | `VectorDatabaseService.vectorExists(entityType, entityId)` |

The module does not embed or write vectors directly. It resolves the same descriptor and approved
projection used by annotation lifecycle indexing, then submits a class-free `AIIndexDocument`.
The indexing worker owns embedding, vector upsert, ordering, retry, and dead-letter behavior.

Declare backfill ownership on the entity:

```java
@Entity
@AICapable(
    entityType = "knowledge-article",
    migrationRepository = KnowledgeArticleRepository.class
)
public class KnowledgeArticle {
    // approved identity, searchable fields, and context
}
```

Startup fails on duplicate entity registrations, descriptor/entity-type disagreement, or an
incompatible repository binding.

## Job Lifecycle

Legal operator transitions:

| Current state | Allowed next state | Notes |
| --- | --- | --- |
| `RUNNING` | `PAUSED` | Stops when the worker next reloads the job. |
| `PAUSED` | `RUNNING` | Submits a new worker from the stored page/filter state. |
| `PENDING`, `RUNNING`, `PAUSED` | `CANCELLED` | Marks `completedAt` and stops future work. |
| `COMPLETED`, `FAILED`, `CANCELLED` | none | Terminal jobs stay terminal. |

Terminal jobs must not be restarted or mutated by pause/cancel calls. This protects release backfills from
duplicate workers and accidental replays after an operator has already cancelled or completed a run.

## Counting Semantics

`totalEntities` is the source repository count at job creation time.

`processedEntities` counts source records examined by the migration worker. This includes records that
are skipped because:

- filters rejected them;
- `reindexExisting=false` and the vector already exists;
- an entity id cannot be resolved.

The migration module separates failure evidence:

- `projectionFailures`: records rejected before durable handoff;
- `enqueueFailures`: records whose gateway submission failed;
- `failedEntities`: the sum of those record failures.

The module does not expose a separate queued counter. Queue handoff is verified through indexing
metrics and the `aifabricEntities` actuator endpoint.

`failedEntities` counts source records that could not be filtered, serialized, checked, or enqueued.

Completed jobs report `percentComplete=100` after all source pages have been examined. Operators should
compare processed and failed counts with indexing queue metrics to understand how many records were
actually handed to indexing.

## Filtering

Filtering is intentionally fail-closed.

For each entity type, configure one of:

- a custom `MigrationFilterPolicy`; or
- `ai.migration.entity-fields.<entityType>.created-at-field` for the default date/id filter logic.

Stored filter JSON is required to deserialize successfully. Malformed persisted filter JSON raises an error
instead of being treated as absent filters, because dropping filters during resume could broaden a backfill.

## Failure Semantics

- Missing `entityType`, unknown descriptor/repository registration, disabled indexing, or missing
  migration filter support fails before job start.
- Source-record failures are counted and the job continues to the next record.
- Unhandled repository/job failures mark the job `FAILED` and store a nonblank error summary.
- Projection and enqueue failures are counted separately; provider retry/dead-letter behavior belongs
  to `ai-fabric-indexing`.
- Worker interruption preserves the Java interrupt flag during rate limiting.
- Durable payloads contain approved projected fields, not serialized domain entities or Java classes.

## Verification

Run the module tests with dependencies:

```bash
mvn -f ai-infrastructure-module/pom.xml -pl ai-fabric-migration -am test -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
```

The test suite should cover:

- pause, resume, cancel, and terminal-state rejection;
- filter matching and fail-closed malformed filter JSON;
- skipped existing vectors when `reindexExisting=false`;
- queue enqueue payloads and queue failure counts;
- descriptor and migration-repository validation;
- projection versus enqueue failure counters;
- class-free, approved queue payloads;
- progress reporting for skipped, failed, and completed rows.

## Production Cutover

For the 0.4 greenfield queue contract:

1. stop old indexing and migration workers;
2. retain the authoritative source database;
3. discard old queue rows, leases, and generated vectors for affected entity types;
4. create the current `ai_indexing_queue` and `ai_indexing_entity_state` schema;
5. start the application and verify descriptor readiness;
6. run backfill from source records;
7. compare processed/projection/enqueue counts with queue completion and dead-letter metrics;
8. query representative records and prove required metadata and deletion behavior.

Do not deserialize or adapt a 0.3 queue payload. Rebuild generated vector state from source records.
