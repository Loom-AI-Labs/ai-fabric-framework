---
id: prod-04
slug: migration-backfill
title: Backfill Existing Application Data
track: production
order: 4
durationMinutes: 95
availability: preview
courseVersion: 0.3.3-course.2-beta
frameworkVersion: 0.3.3
frameworkTag: ai-fabric-framework-v0.3.3
courseSourceTag: ai-fabric-course-v0.3.3.2
starterRef: course-0.3.3-p03-prompt-overlays
solutionRef: course-0.3.3-p04-migration-backfill
requiresOpenAi: false
requiresDocker: false
optionalProviderExercises: []
sourcePaths:
  - docs/course/production/04-migration-backfill/notebooklm/AI_FABRIC_STATE_STORAGE_MAP_NOTEBOOKLM_SCRIPT.md
  - ai-infrastructure-module/ai-fabric-migration/src/main/java/ai/fabric/migration/service/DataMigrationService.java
  - ai-infrastructure-module/ai-fabric-migration/src/main/java/ai/fabric/migration/domain/MigrationJob.java
  - ai-infrastructure-module/ai-fabric-core/src/main/java/ai/fabric/indexing/api/AIEntityIndexingGateway.java
  - ai-infrastructure-module/ai-fabric-core/src/main/java/ai/fabric/indexing/descriptor/AIEntityDescriptorRegistry.java
  - ai-infrastructure-module/ai-fabric-indexing/src/main/java/ai/fabric/indexing/queue/IndexingQueueService.java
  - ai-infrastructure-module/ai-fabric-core/src/main/java/ai/fabric/annotation/AICapable.java
theoryVideoIds:
  - state-storage-map
assistant:
  mode: implement
  implementationPrompt: assistant-prompt.md
  reviewPrompt: assistant-review-prompt.md
  validationStatus: passed
knowledgeCheck:
  source: knowledge-check.yml
  required: true
  passingScorePercent: 80
video:
  status: published
  generator: NotebookLM
  purpose: pre-lesson-theory
  placement: before-lab
  targetDurationMinutes: 11
  title: State And Storage In An AI Fabric Application
  publicUrl: https://www.youtube.com/watch?v=epjF29WfEUM
  transcript: notebooklm/AI_FABRIC_STATE_STORAGE_MAP_NOTEBOOKLM_SCRIPT.md
  sourceManifest: notebooklm/source-manifest.yml
---

# Backfill Existing Application Data

## Start Here

Your support database already contains useful articles before AI Fabric is installed. Saving those
rows again just to trigger indexing is unsafe and unrealistic. This lesson adds an explicit,
admin-scoped migration that reads existing application-owned rows, creates durable indexing work,
and proves when semantic evidence is actually retrievable.

> Start from `course-0.3.3-p03-prompt-overlays`; compare with
> `course-0.3.3-p04-migration-backfill`. The checkpoint passed 55 deterministic tests and a packaged
> ONNX/Lucene migration smoke. No external API key or Docker service is required.

## The Four Observable States

```text
application source DB        migration DB          indexing DB           vector provider
knowledge_article rows  ->   migration_job    ->   ai_indexing_queue ->  knowledge-article vectors
business truth               scan/control          durable work          derived evidence
```

These stores answer different questions:

- source rows say what the application currently knows;
- the migration job says what was scanned and whether the job can pause, resume, or cancel;
- the indexing queue says whether asynchronous embedding/indexing work is pending or failed;
- the vector provider says what evidence can be retrieved now.

`COMPLETED` on a migration job means source scanning and enqueueing completed. It does not, by
itself, prove the queue drained or vectors are queryable.

## What You Will Prove

- only a principal with `migration:admin` can control a backfill;
- `@AICapable.migrationRepository` binds the source repository explicitly;
- bounded batches and optional filters use stable source IDs;
- pause, resume, cancel, missing-job, and invalid-transition outcomes are visible;
- private `internalNotes` never enter the durable queue payload;
- tenant-filtered retrieval works after queue drain;
- rerunning with `reindexExisting=false` keeps vector IDs and queue counts stable;
- job, queue, and vector readiness remain separate in diagnostics.

## Prerequisites

```bash
git clone https://github.com/Loom-AI-Labs/ai-fabric-course-support-assistant.git
cd ai-fabric-course-support-assistant
git switch --detach course-0.3.3-p03-prompt-overlays
git switch -c lesson/prod-04-migration-backfill
./mvnw --batch-mode --no-transfer-progress clean verify
```

## Step 1: Add The Migration Module

Add `io.github.loom-ai-labs:ai-fabric-migration` at the same `${ai-fabric.version}` used by
the other framework modules. Do not add a framework source checkout or an unpublished example
module.

The standalone application must scan the persistence types it actually uses:

```java
@EntityScan(basePackages = {
    "dev.aifabric.course.support",
    "ai.fabric.chat.domain",
    "ai.fabric.entity",
    "ai.fabric.migration.domain"
})
@EnableJpaRepositories(basePackages = {
    "dev.aifabric.course.support",
    "ai.fabric.repository",
    "ai.fabric.migration.repository"
})
```

For AI Fabric `0.4.0`, this explicit registration keeps the learner app's entities, chat-session
entities, indexing queue, and migration jobs in one intentional persistence unit.

## Step 2: Bind The Source Repository

Update the article annotation:

```java
@AICapable(
    entityType = KnowledgeArticle.ENTITY_TYPE,
    migrationRepository = KnowledgeArticleRepository.class
)
```

Add a stable `createdAt` field so date filters have a real source field. Keep `id` stable across
runs. Mark identity with `@AIIdentity`, title/body with `@AISearchable`, and only approved structured
fields with `@AIContext`. Leave `internalNotes` unannotated. Migration uses the canonical projection
and stores a class-free `AIIndexDocument`, not a serialized entity. `@JsonIgnore` may still protect
application JSON, but it is not the indexing security boundary.

## Step 3: Configure Bounded Work

```yaml
ai:
  indexing:
    enabled: true
    async-worker:
      enabled: true
      fixed-delay: 100ms
      batch-size: 25
  migration:
    enabled: true
    default-batch-size: 25
    default-rate-limit: 0
    max-concurrent-jobs: 1
    entity-fields:
      knowledge-article:
        created-at-field: createdAt
```

The migration service needs field metadata even when a request has no filter, because it validates
the entity's filter contract before scanning. Keep concurrency and batch limits conservative until
you have measured source-database, embedding, and vector-provider capacity.

## Step 4: Expose An Application-Owned Admin Boundary

Create an endpoint fixed to `knowledge-article`; do not accept an arbitrary entity class or tenant
from the request. Resolve the server-verified principal and require `migration:admin` before calling
`DataMigrationService`.

The checkpoint exposes:

```text
POST /api/admin/migrations/knowledge-articles
GET  /api/admin/migrations/knowledge-articles
GET  /api/admin/migrations/knowledge-articles/{jobId}
POST /api/admin/migrations/knowledge-articles/{jobId}/pause
POST /api/admin/migrations/knowledge-articles/{jobId}/resume
POST /api/admin/migrations/knowledge-articles/{jobId}/cancel
```

Return `404` for an unknown/non-knowledge job and `409` for an invalid transition. The response can
combine migration progress with queue and vector counts, but label each field by what it proves.

## Step 5: Run The Backfill

Start the packaged application or use `./mvnw spring-boot:run`, then:

```bash
export COURSE_TOKEN=course-alex-local-token
curl -s -X POST http://localhost:8080/api/demo/reset
curl -s -X POST http://localhost:8080/api/demo/seed
curl -s -X POST http://localhost:8080/api/admin/migrations/knowledge-articles \
  -H "Authorization: Bearer $COURSE_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"batchSize":3,"rateLimit":0,"reindexExisting":false}'
```

Poll the returned `jobId`. First wait for `status=COMPLETED`; then wait for:

```json
{
  "currentIndexedVectors": 9,
  "pendingQueueEntries": 0,
  "processingQueueEntries": 0,
  "deadLetterQueueEntries": 0,
  "indexingCaughtUp": true,
  "fullSourceVectorCoverage": true
}
```

For a filtered migration, `fullSourceVectorCoverage` is intentionally absent. One selected row does
not prove all source rows have vector evidence.

## Step 6: Prove Retrieval And Idempotency

```bash
curl -s 'http://localhost:8080/api/knowledge/search?q=I%20cannot%20sign%20in%20after%20too%20many%20attempts' \
  -H "Authorization: Bearer $COURSE_TOKEN"
```

Expected first evidence ID: `policy-account-lockout-01`. A Tenant Red principal can retrieve its own
VPN article but cannot see Tenant Blue evidence or administer migration.

Run the same full migration again with `reindexExisting=false`. Expect nine vectors and the same
completed queue-entry count. AI Fabric `0.4.0` reports scanned, projection-failed, enqueue-failed,
and total failed rows but does not expose an
exact per-job skipped count. Do not derive `skipped = processed - queued`; filtered scans and
existing-vector decisions make that number misleading.

## Intentional Failure

Use Riley's token against the start endpoint and expect HTTP `403`. Then try pausing a completed job
and expect HTTP `409`. Neither request may create new queue entries or vectors.

As a second operational failure, stop the indexing worker after the job starts. The job may reach
`COMPLETED`, while `pendingQueueEntries` stays positive and `indexingCaughtUp` stays false. That is
the correct visible result.

## Verification

```bash
./mvnw --batch-mode --no-transfer-progress clean verify
./scripts/download-onnx-model.sh
COURSE_SMOKE_USE_EXISTING_JAR=true ./scripts/smoke-packaged.sh
jq '.migration, .migrationRerun' \
  target/course-release-evidence/packaged-smoke-summary.json
```

Expected checkpoint: `course-0.3.3-p04-migration-backfill`; capability
`migrationBackfill=true`.

## Done When

- all nine pre-existing source rows can be backfilled without rewriting them;
- admin denial and lifecycle transition failures are tested;
- queue payloads exclude private notes;
- job completion and indexing readiness are reported separately;
- tenant-safe search returns the expected stable evidence IDs;
- an idempotent rerun adds no duplicate work or vectors;
- 55 tests and the packaged ONNX/Lucene migration smoke pass.

## Reset

```bash
./scripts/reset-course.sh
git switch --detach course-0.3.3-p03-prompt-overlays
```

## Troubleshooting

**No `MigrationJobRepository` bean:** include `ai.fabric.migration.repository` in repository scanning
and `ai.fabric.migration.domain` in entity scanning.

**Chat entities are no longer managed:** explicit `@EntityScan` replaces implicit package scanning;
include `ai.fabric.chat.domain` as well as the application and indexing packages.

**Job is complete but search is empty:** inspect pending, processing, failed, and dead-letter queue
counts. Wait for `indexingCaughtUp=true`; do not rerun blindly.

**Private text appears in a queue payload:** fix the typed field destinations or registered custom
projector before continuing. Queue payloads must contain only the approved projection.

**Filtered migration reports nine processed rows:** in `0.4.0`, processed is scanned source rows. Use
vector IDs/counts to prove selected output and do not publish a fictional skipped count.

## Next Lesson

PROD-05 keeps this initial vector state synchronized when trusted application operations create,
update, or delete source rows.
