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
| Source repositories | `EntityRepositoryRegistry` over Spring Data repositories |
| Filtering | `MigrationFilterPolicy` or `ai.migration.entity-fields.*` |
| Indexing handoff | `IndexingQueueService` with `IndexingStrategy.ASYNC` |
| Existing-vector skip check | `VectorDatabaseService.vectorExists(entityType, entityId)` |

The module does not embed or write vectors directly. It serializes each accepted source entity into an
`IndexingRequest`, and the indexing worker owns extraction, embedding, vector upsert, retry, and dead-letter
behavior.

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

The migration module does not expose a separate "queued" counter in V1; queue handoff is verified by
indexing logs/metrics and `IndexingQueueService` behavior.

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

- Missing `entityType`, unknown AI entity config, or missing migration filter support fails before job start.
- Source-record failures are counted and the job continues to the next record.
- Unhandled repository/job failures mark the job `FAILED` and store a nonblank error summary.
- Queue failures count as record failures; indexing retry/dead-letter behavior belongs to `ai-fabric-indexing`.
- Worker interruption preserves the Java interrupt flag during rate limiting.

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
- progress reporting for skipped, failed, and completed rows.
