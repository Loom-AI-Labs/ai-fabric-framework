# LoomAI AI Fabric 0.8 Document Indexing Migration Runbook

## Purpose

This runbook moves LoomAI from AI Fabric `0.7.1` to `0.8.0` and, as a separate opt-in gate, adopts
the governed document-indexing boundary for one internal source workflow.

Read these first:

- [AI Fabric 0.8 release notes](../../release-notes/0.8.0.md)
- [LoomAI 0.8 upgrade notes](../../release-notes/LOOMAI_PLATFORM_AI_FABRIC_0_8_0_UPGRADE_NOTES.md)
- [Document indexing 0.8 migration guide](DOCUMENT_INDEXING_0_8_MIGRATION_GUIDE.md)
- [Document Ingestion Workbench](../../../examples/real-apps/document-ingestion-workbench/README.md)

## Scope Decision

Do not migrate every record that LoomAI calls a document.

Keep structured products, policies, reviews, marketplace rows, Shopify records, and other existing
entity projections on Data Sync when they already have stable IDs and lifecycle behavior. Adopt the
new bridge for sources that actually need Spring AI reading, transformation, or splitting, such as
trusted text/JSON files or a future explicitly approved reader.

The first canary should be read-only from a product perspective: ingest one trusted knowledge
source and retrieve evidence from it. Do not combine the first canary with actions or specialist
writes.

## Gate A: Dependency-Only Upgrade

### A1. Record the LoomAI baseline

```bash
git status --short --branch
git rev-parse HEAD
```

Preserve existing user changes. Record the currently deployed Platform, runtime, and product image
commits before editing.

### A2. Verify the immutable release

Verify the release tag and resolve the BOM from an empty cache:

```bash
git ls-remote --tags \
  https://github.com/Loom-AI-Labs/ai-fabric-framework.git \
  refs/tags/ai-fabric-framework-v0.8.0

EMPTY_REPO="$(mktemp -d)"
mvn -Dmaven.repo.local="$EMPTY_REPO" \
  dependency:get \
  -Dartifact=io.github.loom-ai-labs:ai-fabric-bom:0.8.0:pom
```

Do not continue until both succeed. Do not use a local framework install as publication evidence.

### A3. Update LoomAI version sources

At minimum, update these current version sources from `0.7.1` to `0.8.0`:

```text
ai-fabric-product/pom.xml
ai-infrastructure-module/pom.xml
Platfrom/backend/src/main/resources/application.yml
Platfrom/backend/src/main/java/com/ai/fabric/platform/backend/deployment/entity/DeploymentVersionEntity.java
Platfrom/backend/src/main/java/com/ai/fabric/platform/backend/deployment/service/DeploymentConfigCompiler.java
```

Update associated assertions and generated-deployment expectations in:

```text
Platfrom/backend/src/test/java/com/ai/fabric/platform/backend/deployment/
Platfrom/backend/src/test/java/com/ai/fabric/platform/backend/deployment/service/
ai-infrastructure-module/ai-fabric-runtime/src/test/
```

Search after editing:

```bash
rg -n '0\.7\.1|AI_FABRIC_FRAMEWORK_VERSION' \
  ai-fabric-product \
  ai-infrastructure-module \
  Platfrom/backend
```

Keep historical release evidence unchanged when it intentionally describes `0.7.1`.

### A4. Do not adopt document APIs yet

For Gate A:

- add no new document endpoints;
- add no manifest table;
- change no marketplace or Shopify sync behavior;
- change no vector IDs;
- reindex no existing entity spaces; and
- preserve current `ai.indexing.*`, Data Sync, and vector provider settings.

The new document auto-configuration is conditional. Existing behavior remains unchanged unless a
consumer has Spring AI document classes, indexing/vector/embedding prerequisites, and document
support enabled.

### A5. Run the LoomAI reactors normally

```bash
mvn -f ai-fabric-product/pom.xml clean verify
mvn -f ai-infrastructure-module/pom.xml clean verify
mvn -f Platfrom/backend/pom.xml clean verify
```

Do not use `-DskipTests` or `maven.test.skip`.

### A6. Build from Central and deploy

Build the release candidate from a clean Maven cache. Inspect dependency trees and prove every AI
Fabric module resolves to `0.8.0`. Deploy the exact immutable LoomAI commit.

### A7. Base canary

Verify:

- Platform and runtime health;
- framework version and source commit readback;
- existing Data Sync upsert/update/delete;
- vectorization checkpoints, failures, and reconciliation;
- marketplace and Shopify sync;
- runtime indexing overview and per-work status;
- tenant/deployment retrieval isolation;
- chat, RAG, specialists, chains, actions, receipts, and reviews; and
- generated deployments use `0.8.0`.

Gate A is complete only when current behavior is preserved. No document-ingestion product claim is
allowed from Gate A alone.

## Gate B: One Document-Ingestion Canary

### B1. Choose a bounded source

Choose one internal text or JSON source with:

- a server-owned source ID;
- a monotonically increasing source version;
- a trusted tenant and deployment;
- a known visibility policy;
- small, representative content;
- no secrets or raw personal data; and
- a clear owner who can validate retrieval and deletion.

Do not start with PDFs, remote URLs, broad folders, customer uploads, or an unmanaged crawler.

### B2. Add explicit dependencies

In the owning runtime/product module, add:

```xml
<dependency>
  <groupId>io.github.loom-ai-labs</groupId>
  <artifactId>ai-fabric-indexing</artifactId>
</dependency>
<dependency>
  <groupId>org.springframework.ai</groupId>
  <artifactId>spring-ai-commons</artifactId>
</dependency>
```

Use the AI Fabric BOM for versions. Do not add a direct Spring AI `VectorStore`; LoomAI's selected AI
Fabric vector provider remains the destination.

### B3. Configure conservative bounds

Begin below the framework ceilings:

```yaml
ai:
  indexing:
    enabled: true
    documents:
      enabled: true
      max-documents-per-plan: 20
      max-chunks-per-plan: 100
      max-content-length-per-chunk: 8000
      max-total-content-length: 250000
      max-metadata-entries-per-chunk: 24
      max-metadata-value-length: 256
      default-splitter:
        enabled: true
        chunk-size: 800
        min-chunk-size-chars: 200
        min-chunk-length-to-embed: 5
      metadata:
        allowed-application-keys:
          - originalFilename
          - locale
          - sourceCategory
        warn-on-drop: true
```

Treat each allowed key as retrieval-visible evidence. Never allow tenant, deployment, role, source
identity, lifecycle status, or visibility keys from parser/user metadata.

### B4. Add application-owned persistence

Persist source and manifest state in LoomAI. The minimum durable information is:

```text
source record
  source_id
  tenant_id
  deployment_id
  current_source_version
  active_source_version
  lifecycle_status
  trusted_storage_reference
  bounded_failure_code/message

manifest record
  manifest_id
  plan_id
  source_id
  source_version
  entity_type
  tenant_id
  visibility
  lifecycle_status
  content-free chunk entries:
    source_document_id
    chunk_id
    chunk_index
    entity_id
    content_fingerprint
  accepted indexing work IDs
  accepted delete work IDs
```

Do not store chunk text, embeddings, local absolute paths, credentials, parser exceptions, or the
full `DocumentIngestionPlan` in the manifest table.

Existing LoomAI dataset handles, sync runs, and tracked-document records are useful ownership
patterns, but do not silently change their schema or semantics. Introduce a dedicated canary model
or a consciously versioned extension.

### B5. Resolve only trusted resources

Resolve the source record to a server-controlled Spring `Resource`, then construct its reader only
through the trust boundary:

```java
SpringAiTrustedResourcePolicy resourcePolicy =
    SpringAiTrustedResourcePolicy.builder()
        .allowedFileRoot(configuredTrustedRoot)
        .allowClasspath(false)
        .build();

DocumentReader reader = readerFactory.textReader(resource, resourcePolicy);
```

Use `jsonReader(...)` only for an approved JSON content-key contract. The policy must reject remote
URLs and files outside configured roots.

### B6. Prepare without side effects

Create trusted options on the server:

```java
DocumentIngestionPlan plan = documentAdapter.plan(
    reader,
    SpringAiDocumentIndexingOptions.builder()
        .entityType("deployment-document")
        .sourceId(source.id())
        .sourceVersion(source.version())
        .sourceName(source.displayName())
        .tenantId(authenticatedTenantId)
        .visibility("internal")
        .operation(AIProcessOperation.UPDATE)
        .allowedMetadataKey("originalFilename")
        .metadata("originalFilename", source.originalFilename())
        .correlationId(correlationId)
        .build()
);

DocumentIngestionManifest manifest = documentAdapter.manifest(plan);
```

Keep the plan request-scoped/server-side. Persist the manifest before submission. Preview must be an
application DTO containing only bounded excerpts and approved metadata.

Assert that preview produces no queue entry, embedding request, vector write, Data Sync call, or
source activation.

### B7. Submit and reconcile

Submit through the existing queue:

```java
List<IndexingQueueEntry> accepted = documentQueueAdapter.submit(
    plan,
    IndexingStrategy.ASYNC,
    LocalDateTime.now()
);
```

Persist every accepted work ID. A partial submission exception includes already accepted IDs; retain
and reconcile them rather than pretending the operation was atomic.

Use `IndexingWorkQuery` to reconcile each ID. Activate the candidate only after every chunk reaches
a successful terminal outcome. Surface bounded failure evidence to operators when work fails or
requires intervention.

### B8. Replace new-first

For source version `N+1`:

1. Keep version `N` active.
2. Prepare and persist the `N+1` manifest.
3. Submit and reconcile every `N+1` chunk.
4. If any work fails, mark `N+1` failed and leave `N` active.
5. If all work succeeds, activate `N+1` and mark `N` superseded.
6. Submit exact deletes from the version `N` manifest.
7. Reconcile every delete before marking `N` deleted.

`0.8.0` permits a short old/new overlap. Keep an application-side active-version retrieval filter
when the product cannot tolerate that overlap.

### B9. Delete exactly

```java
List<IndexingQueueEntry> deletes = documentQueueAdapter.submitDeletes(
    manifest,
    IndexingStrategy.ASYNC,
    LocalDateTime.now(),
    Instant.now()
);
```

Do not delete by broad entity type, source metadata scan, or guessed chunk count. Mark a source
deleted only after every manifest entity ID has a successful terminal delete outcome.

### B10. Retrieve with trusted filters

Build retrieval metadata from authenticated server context. Require the expected tenant,
deployment, entity type, source/version lifecycle, and visibility constraints before evidence is
passed to an LLM or specialist.

Return evidence with bounded fields such as source ID, source version, chunk ID, entity ID, score,
and approved metadata. Do not expose storage paths or internal plan content.

## Gate B Test Matrix

Automate these scenarios:

| Scenario | Required result |
| --- | --- |
| Trusted text preview | Bounded chunks, no queue/vector side effect |
| Trusted JSON preview | Approved content keys and metadata only |
| Resource outside trusted root | Fail closed |
| Remote URL | Fail closed |
| Oversized source/chunk/metadata | Stable bounded failure |
| Parser tries protected metadata override | Trusted values win; warning/error is visible |
| Repeat same source/version/content | Same plan, chunk, entity, and manifest identities |
| Initial index | Durable work IDs, then active only after completion |
| Tenant B query for Tenant A source | No evidence |
| Failed replacement | Old version remains active |
| Successful replacement | New active before exact old deletion |
| Partial queue submission | Accepted IDs retained and reconciled |
| Delete | Every exact manifest entity ID removed |
| Restart during indexing/deletion | Persisted state resumes reconciliation |

Use the framework's packaged workbench smoke as a reference, but add LoomAI tests around its own
database, auth context, runtime API, and vector provider.

## Gate C: Product Promotion

Promote beyond the canary only when:

- the source type has a named product owner;
- corpus size and parsing behavior are measured;
- quotas and retention are defined;
- tenant and deployment isolation has adversarial proof;
- operator retry/delete/reconcile paths exist;
- observability distinguishes planning, accepted work, active evidence, and deletion;
- restart recovery passes on the production database and vector provider; and
- rollback has been rehearsed.

Add PDF/OCR, object storage, remote connectors, or scheduled crawling only as separate, reviewed
features. They are not implied by Gate C.

## Rollback

### Gate A rollback

Because existing Data Sync identities and schemas are unchanged, roll back the LoomAI application
image to its last `0.7.1` build. Do not rewrite historical deployment records.

### Gate B rollback

1. Stop new document submissions.
2. Reconcile all accepted work IDs.
3. Use persisted manifests to delete canary `aidoc-*` vectors exactly.
4. Verify no canary evidence remains for every tenant/deployment.
5. Disable `ai.indexing.documents.enabled` for the canary service.
6. Keep source and manifest audit records according to LoomAI retention policy.
7. Roll back application code only after cleanup is proven.

Never roll back by dropping indexing queue tables or deleting a whole vector space shared with other
entity types.

## Completion Evidence

Attach these to the LoomAI release record:

- immutable framework tag and Central resolution output;
- exact LoomAI commits and deployed image digests;
- dependency trees proving `0.8.0` only;
- normal reactor test results;
- Gate A canary output;
- Gate B lifecycle and tenant-isolation output when adopted;
- manifest/work-ID evidence for replacement and delete;
- runtime/framework version and commit readback; and
- explicit list of deferred source types/readers.
