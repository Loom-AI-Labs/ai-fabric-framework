# Document Indexing 0.8 Migration Guide

## Purpose

AI Fabric 0.8 replaces the early Spring AI document convenience adapter with a governed preparation
and lifecycle boundary. Use this guide when upgrading an application that called
`SpringAiDocumentIndexingAdapter.enqueue(...)` or persisted old `springai-*` chunk IDs.

The new boundary keeps responsibilities explicit:

- Spring AI owns document reading, transformation, and splitting.
- AI Fabric owns trusted preparation, protected metadata, deterministic identity, canonical
  `AIIndexDocument` work, queue integration, and exact delete helpers.
- Your application or LoomAI owns source storage, manifest persistence, status reconciliation,
  activation, replacement, and operator workflow.

## Before You Upgrade

Inventory these items in the consuming application:

- every call to `SpringAiDocumentIndexingAdapter.enqueue(...)` or `toIndexDocuments(...)`;
- every reference to `springai-*` entity IDs;
- every `_springAi*` metadata key;
- source/version records and persisted chunk IDs;
- code that treats queue acceptance as completed indexing;
- delete/reindex flows that delete old vectors before candidate indexing succeeds.

Drain existing queue work before changing identities. If the old application persisted exact old
entity IDs, delete them while the old runtime can still address them. Otherwise create an explicit,
audited cleanup operation before reindexing. Do not rely on a broad metadata scan or leave old and
new identities searchable indefinitely.

## Dependency And Configuration

Use the 0.8 BOM once published and include `ai-fabric-indexing`. Spring AI remains optional; include
`spring-ai-commons` or a reader module only when the application uses the Spring AI bridge.

```yaml
ai:
  indexing:
    enabled: true
    documents:
      enabled: true
      max-documents-per-plan: 100
      max-chunks-per-plan: 500
      max-content-length-per-chunk: 10000
      max-total-content-length: 1000000
      max-metadata-entries-per-chunk: 32
      max-metadata-value-length: 512
      default-splitter:
        enabled: true
        chunk-size: 800
        min-chunk-size-chars: 200
        min-chunk-length-to-embed: 5
      metadata:
        allowed-application-keys:
          - originalFilename
          - locale
        warn-on-drop: true
```

Application options may tighten these bounds but cannot exceed the configured framework ceilings.
Protected `_aiDocument*` keys are never application-allowlist entries.

## Replace Direct Enqueue With Plan And Submit

Before 0.8, the adapter parsed and submitted in one call. In 0.8, planning is side-effect free and
queueing is explicit:

```java
DocumentIngestionPlan plan = documentAdapter.plan(
    trustedReader,
    SpringAiDocumentIndexingOptions.builder()
        .entityType("kb")
        .sourceId(source.id())
        .sourceVersion(source.version())
        .sourceName(source.title())
        .tenantId(authenticatedTenantId)
        .visibility("internal")
        .operation(AIProcessOperation.UPDATE)
        .allowedMetadataKey("originalFilename")
        .metadata("originalFilename", source.originalFilename())
        .correlationId(correlationId)
        .build()
);

DocumentIngestionManifest manifest = documentAdapter.manifest(plan);

List<IndexingQueueEntry> accepted = documentQueueAdapter.submit(
    plan,
    IndexingStrategy.ASYNC,
    LocalDateTime.now()
);
```

Keep the full plan server-side. It contains chunk content and is not a controller DTO. Persist the
content-free manifest and the returned queue work IDs in application-owned storage.

## Preview Safely

Build an application DTO from `plan.chunks()` with strict content and chunk limits. A preview should
contain only what the UI requires, such as:

- source document ID;
- chunk ID and index;
- bounded content excerpt;
- content length and fingerprint;
- approved metadata keys;
- safe warning codes.

Never serialize the full plan, local resource path, embedding vectors, dropped metadata values,
credentials, or parser/provider exceptions.

## Reconcile Existing Work Status

`DocumentIndexingQueueAdapter.submit(...)` returns existing `IndexingQueueEntry` records. That proves
durable acceptance, not successful vector persistence.

Persist each work ID and inspect it through `IndexingWorkQuery`:

- keep the candidate `INDEXING` while any item is non-terminal;
- activate only when every item is a successful terminal state;
- expose `errorCode` and bounded failure evidence when an item requires operator review;
- never report a fallback success after parser, embedding, queue, or vector failure.

## Use New-First Replacement

Use this application-owned transition:

```text
old manifest ACTIVE
  -> prepare candidate plan and persist DRAFT manifest
  -> submit candidate UPSERT work
  -> wait for every candidate work item to succeed
  -> mark candidate ACTIVE
  -> mark old manifest SUPERSEDED
  -> submit exact old-manifest deletes
  -> wait for every delete to succeed
  -> mark old manifest DELETED
```

If candidate planning, submission, embedding, or vector persistence fails, mark that candidate
failed and retain the old active manifest. Cleanup any partially accepted candidate IDs through its
exact manifest.

AI Fabric 0.8 permits a short overlap between activation and old-version deletion. Applications that
cannot tolerate overlap should keep an application-side active-version filter; a generic framework
filter is not part of 0.8.

## Delete By Exact Manifest Identity

Do not delete documents using broad entity-type or source metadata scans. Convert the persisted
manifest to payload-free canonical delete work:

```java
List<IndexingQueueEntry> deletes = documentQueueAdapter.submitDeletes(
    manifest,
    IndexingStrategy.ASYNC,
    LocalDateTime.now(),
    Instant.now()
);
```

The helper uses the exact final entity IDs and produces `AIIndexDocument` values with `DELETE` work,
no semantic content, and no vector metadata. Mark the source deleted only after every returned work
ID reaches a successful terminal state.

## Metadata Migration

Replace old provider-specific evidence keys with these protected keys:

| Old key | 0.8 key |
| --- | --- |
| `_springAiSourceId` | `_aiDocumentSourceId` |
| source version inferred from parser metadata | `_aiDocumentSourceVersion` |
| `_springAiSourceName` | `_aiDocumentSourceName` |
| `_springAiDocumentId` | `_aiDocumentId` |
| `_springAiChunkIndex` | `_aiDocumentChunkIndex` |
| `_springAiChunkCount` | `_aiDocumentChunkCount` |
| `_springAiContentFingerprint` | `_aiDocumentContentFingerprint` |
| none | `_aiDocumentChunkId` |
| untrusted/custom tenant key | `_aiDocumentTenantId` from trusted options |
| untrusted/custom visibility key | `_aiDocumentVisibility` from trusted options |

Parser and application metadata cannot override these values. Additional metadata is allowlist-only,
bounded, scalar, and safe to expose as retrieval evidence.

## Identity Migration

New entity IDs begin with `aidoc-` and include entity type, source ID, source version, chunk position,
and content fingerprint. The same source/version/content produces the same plan and chunk identities;
changing version or content changes them.

Because old `springai-*` IDs are different, use this cutover order:

1. pause old document ingestion;
2. drain old work;
3. export or inspect exact old IDs;
4. delete old IDs with the old runtime or an audited exact cleanup;
5. deploy 0.8 contracts;
6. re-prepare and reindex every retained source;
7. verify source/version/chunk evidence and tenant isolation;
8. resume ingestion.

There is no dual-write or deprecated-ID compatibility mode.

## What Is Not Included

AI Fabric 0.8 does not include framework source storage, manifest persistence, a generic reindex state
machine, remote crawling, a document-specific Data Sync mapper, PDF/OCR, or a shared REST preview
schema. Do not infer those features from the ETL bridge.

For a complete reference implementation and executable requests, use
`examples/real-apps/document-ingestion-workbench`.

## Verification

```bash
mvn -B -V --no-transfer-progress \
  -f ai-infrastructure-module/pom.xml \
  -pl ai-fabric-indexing -am clean test

mvn -B -V --no-transfer-progress \
  -f examples/real-apps/pom.xml \
  -pl document-ingestion-workbench -am clean package

.github/scripts/smoke-document-ingestion-workbench.sh
```

The full framework reactor and normal application tests remain required before release. A keyed
OpenAI embedding run is useful additional evidence, but it must not replace the deterministic gate.
