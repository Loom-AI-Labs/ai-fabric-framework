---
id: prod-05
slug: live-data-sync
title: Keep Application Data Synchronized
track: production
order: 5
durationMinutes: 85
availability: preview
courseVersion: 0.3.3-course.1-beta
frameworkVersion: 0.3.3
frameworkTag: ai-fabric-framework-v0.3.3
courseSourceTag: ai-fabric-course-v0.3.3.1
starterRef: course-0.3.3-p04-migration-backfill
solutionRef: course-0.3.3-p05-live-data-sync
requiresOpenAi: false
requiresDocker: false
optionalProviderExercises: []
sourcePaths:
  - docs/course/production/05-live-data-sync/notebooklm/AI_FABRIC_MIGRATION_BACKFILL_LIVE_SYNC_NOTEBOOKLM_SCRIPT.md
  - ai-infrastructure-module/ai-fabric-data-sync/src/main/java/ai/fabric/datasync/service/DataSyncService.java
  - ai-infrastructure-module/ai-fabric-data-sync/src/main/java/ai/fabric/datasync/AIDataSyncProperties.java
  - ai-infrastructure-module/ai-fabric-data-sync/src/main/java/ai/fabric/datasync/dto/DataSyncVerifiedAuthContext.java
  - ai-infrastructure-module/ai-fabric-data-sync/src/main/java/ai/fabric/datasync/normalize/DataSyncEntityNormalizer.java
theoryVideoIds:
  - live-data-sync
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
  title: From Existing Data To Continuous AI Evidence
  publicUrl: https://www.youtube.com/watch?v=wZ5e0MPSXRI
  transcript: notebooklm/AI_FABRIC_MIGRATION_BACKFILL_LIVE_SYNC_NOTEBOOKLM_SCRIPT.md
  sourceManifest: notebooklm/source-manifest.yml
---

# Keep Application Data Synchronized

## Start Here

PROD-04 established the initial vector state. Your application is still changing: support articles
are created, corrected, and deleted. This lesson routes those trusted source operations through AI
Fabric Data Sync so semantic evidence converges on current business truth.

> Start from `course-0.3.3-p04-migration-backfill`; compare with
> `course-0.3.3-p05-live-data-sync`. The solution passed 58 deterministic tests and a packaged
> ONNX/Lucene sync lifecycle. No external provider key or Docker service is required.

## Migration And Sync Are Different

```text
initial installation                     normal application operation
existing rows -> migration -> queue       authenticated create/update/delete
                         -> vectors                              |
                                                               v
                                                        DataSyncService
                                                               |
                                                               v
                                                            vectors
```

Migration has jobs, scanning progress, filters, pause/resume, and an indexing queue. Data Sync is a
trusted runtime write boundary with per-operation traces, stable IDs, normalization limits, access
control, and direct vector upsert/delete results.

## What You Will Prove

- the application, not the browser, supplies verified auth context and tenant identity;
- only `knowledge-article` is available through the public domain API;
- source rows remain authoritative and do not contain vector/provider details;
- create produces searchable evidence;
- update preserves the logical ID and replaces stale text;
- delete removes both source state and derived evidence;
- unauthorized and low-level raw requests produce no side effects;
- invalid projection rolls back the source transaction;
- batch limit and partial reconciliation failures remain visible;
- traces distinguish correlation ID, source version, and idempotency key.

## Prerequisites

```bash
git clone https://github.com/Loom-AI-Labs/ai-fabric-course-support-assistant.git
cd ai-fabric-course-support-assistant
git switch --detach course-0.3.3-p04-migration-backfill
git switch -c lesson/prod-05-live-data-sync
./mvnw --batch-mode --no-transfer-progress clean verify
```

## Step 1: Enable The Module Conservatively

Add `ai-fabric-data-sync` at `${ai-fabric.version}` and configure:

```yaml
ai:
  data-sync:
    enabled: true
    base-path: /api/internal/ai-data-sync
    max-batch-size: 2
    max-content-chars: 1000
    max-field-value-chars: 2000
    allow-trusted-platform-internal-sync-bypass: false
```

Keep `allow-trusted-platform-internal-sync-bypass=false`. The low-level request contains a verified
auth context DTO, but a public client cannot make its own JSON verified. The solution denies
`/api/internal/ai-data-sync/**` and invokes `DataSyncService` behind an application-owned endpoint.

## Step 2: Extend The Server Policy

Alex receives `data-sync:upsert` and `data-sync:delete`; Riley intentionally does not. Extend the
application `EntityAccessPolicy` only for:

```text
resourceId = vectorSpace:knowledge-article
WRITE  -> data-sync:upsert
DELETE -> data-sync:delete
```

The public controller has no `vectorSpace`, `tenantId`, or auth-context fields. It accepts domain
article inputs, loads the authenticated principal, verifies source ownership, and fixes the vector
space in Java.

## Step 3: Build The Safe Projection

Add a JPA `@Version` field to `KnowledgeArticle`. Use the application article ID as the Data Sync
logical ID and send the current version through `DataSyncIdentity`. Project only fields approved for
AI evidence:

```text
id, title, body, category, tenantId, status, visibility, visibleToUser
```

Do not include `internalNotes`. Derive a content fingerprint for retry evidence, and build
`DataSyncTrace.authContext` from `CoursePrincipal`, never from request JSON.

The response projection may expose safe operational facts: operation, vector space, logical ID,
correlation request ID, idempotency key, source version, and success/error code. It should not echo
the full auth context or provider metadata.

## Step 4: Implement Create And Update

`POST /api/knowledge/articles` creates the source row and upserts its evidence. `PUT
/api/knowledge/articles/{id}` loads the row by authenticated tenant, updates it, flushes the source
version, and upserts the same logical vector ID.

```bash
export COURSE_TOKEN=course-alex-local-token
curl -s -X POST http://localhost:8080/api/knowledge/articles \
  -H "Authorization: Bearer $COURSE_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"id":"article-live-sync","title":"Enroll a passkey","body":"Register a passkey in Security Settings before removing the password.","category":"authentication"}'

curl -s -X PUT http://localhost:8080/api/knowledge/articles/article-live-sync \
  -H "Authorization: Bearer $COURSE_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"title":"Replace a password with a security key","body":"Register the hardware security key, verify it, then revoke the previous login method."}'
```

Expected: both operations report logical ID `article-live-sync`; the update reports source version
`1`; the vector count rises only once; search returns the new hardware-key text and not the original
passkey sentence.

If normalization, embedding, policy, or vector storage fails, throw a runtime exception so the
source transaction rolls back. This narrows inconsistency but does not create a distributed
transaction with an external vector store. Production systems still need reconciliation.

## Step 5: Delete Stale Evidence

```bash
curl -s -X DELETE http://localhost:8080/api/knowledge/articles/article-live-sync \
  -H "Authorization: Bearer $COURSE_TOKEN"
```

The service verifies tenant ownership, removes the vector using the same logical ID, then removes
the source row. The test asserts both are absent and search cannot return the deleted evidence.

## Step 6: Reconcile In Bounded Batches

`POST /api/knowledge/sync/reconcile` accepts only source article IDs and rebuilds operations from
persisted rows in the authenticated tenant. It is a repair path, not a second source API.

Three IDs exceed `max-batch-size=2` and return HTTP `400` with `BATCH_TOO_LARGE`, zero successes, and
zero vector side effects. A two-row test deliberately uses one oversized source projection. The
valid operation succeeds, the invalid one returns `INVALID_REQUEST`, and source rows remain
unchanged. Partial failure is a result to inspect and repair, never a hidden success.

## Intentional Failures

1. Call create with Riley's token. Expect `403`; no row or vector is created.
2. Call `/api/internal/ai-data-sync/upsert` directly. Expect an inaccessible route (`401`, `403`, or
   `404`, depending on the active security entry point), never `2xx`.
3. Submit more than 1,000 normalized characters. Expect `400/INVALID_REQUEST`; the source insert is
   rolled back.
4. Reconcile three IDs. Expect `400/BATCH_TOO_LARGE` and no operation execution.

## Verification

```bash
./mvnw --batch-mode --no-transfer-progress clean verify
./scripts/download-onnx-model.sh
COURSE_SMOKE_USE_EXISTING_JAR=true ./scripts/smoke-packaged.sh
jq '.liveDataSync' target/course-release-evidence/packaged-smoke-summary.json
```

Expected checkpoint: `course-0.3.3-p05-live-data-sync`; capability `liveDataSync=true`.

## Done When

- the raw Data Sync route cannot be used as a public identity boundary;
- the public API derives tenant, auth context, vector space, and metadata server-side;
- create/update/delete preserve source and evidence lifecycle rules;
- update keeps one stable logical vector ID and removes stale text;
- delete leaves no source row or retrievable vector;
- rollback, authorization, batch limit, and partial failure tests pass;
- 58 tests and the packaged ONNX/Lucene lifecycle pass.

## Reset

```bash
./scripts/reset-course.sh
git switch --detach course-0.3.3-p04-migration-backfill
```

## Troubleshooting

**`DataSyncService` is missing:** confirm the module dependency, `ai.data-sync.enabled=true`, vector
provider configuration, and embeddings feature flag.

**Access is always denied:** verify the backend-built auth context has the subject, tenant, and exact
operation scope, and that `EntityAccessPolicy` permits only `vectorSpace:knowledge-article`.

**Update creates a second vector:** omit chunk identity for whole-record sync and keep the logical
article ID stable. A chunk ID intentionally changes the effective target identity.

**Delete says `Not found`:** distinguish idempotent vector absence from source ownership. The domain
API must still verify and delete the correct source row.

**A batch partially fails:** inspect every result, retain source truth, and rerun only failed stable
IDs after correcting the projection/provider problem.

## Next Lesson

PROD-06 turns retrieval expectations, no-source behavior, tenant isolation, and prompt rules into a
repeatable quality scorecard.
