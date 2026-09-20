# LoomAI Platform Upgrade Notes: AI Fabric `0.8.0`

## Decision

LoomAI can upgrade its framework dependency from AI Fabric `0.7.1` to `0.8.0` without replacing
its existing entity Data Sync, marketplace dataset, Shopify, vectorization, chat, RAG, action, or
specialist flows.

The release adds an opt-in document preparation and lifecycle boundary. LoomAI should adopt that
boundary only for sources that need document parsing, splitting, versioned manifests, and exact
chunk deletion. Existing structured records that already flow through AI Fabric Data Sync should
remain on that path.

Use three separate gates:

| Gate | Change | Existing runtime behavior |
| --- | --- | --- |
| A | Upgrade every LoomAI AI Fabric version pin to `0.8.0` | Preserve all current flows |
| B | Add one internal document-ingestion canary | Opt in for one trusted source type |
| C | Promote document ingestion to selected product workflows | Only after lifecycle and tenant proof |

Do not combine the dependency upgrade with a broad Platform document migration.

## Released Artifact Evidence

- Maven group: `io.github.loom-ai-labs`
- BOM: `ai-fabric-bom:0.8.0`
- Git tag: `ai-fabric-framework-v0.8.0`
- GitHub release:
  `https://github.com/Loom-AI-Labs/ai-fabric-framework/releases/tag/ai-fabric-framework-v0.8.0`
- Upgrade baseline: `0.7.1`
- Java: 21
- Spring Boot: 4.1.x
- Spring AI: 2.0.x document ETL bridge

Resolve the BOM from Maven Central before promoting Gate A. Never use a local framework install as
release evidence.

## Audited LoomAI Baseline

The following baseline was inspected on LoomAI branch `Platform-V11` at commit
`8a24468f9789e3a6ce4002faa94a32b9519b0141`:

| Area | Current state | `0.8.0` implication |
| --- | --- | --- |
| Product framework BOM | `ai-fabric-product/pom.xml` pins `0.7.1` | Update to `0.8.0` in Gate A |
| Product services BOM | `ai-infrastructure-module/pom.xml` pins `0.7.1` | Update to `0.8.0` in Gate A |
| Platform default | `platform.ai-fabric.framework-version` defaults to `0.7.1` | Update generated deployment defaults |
| Runtime indexing | Existing queue, worker, and `IndexingWorkQuery` support | Reuse for document work |
| Entity Data Sync | Existing trusted runtime endpoints and vectorization runners | Preserve; do not replace |
| Marketplace datasets | Platform owns dataset handles, sync runs, hashes, and tracked documents | Reuse ownership model where appropriate |
| Shopify documents | Platform owns source records and runtime synchronization | Preserve existing route in Gate A |
| Document adapter usage | No LoomAI use of `SpringAiDocumentIndexingAdapter` was found | No source-level break in Gate A |

This means `0.8.0` is a safe dependency upgrade after normal regression testing. The breaking
document identity change affects only consumers of the earlier experimental Spring AI document
adapter, which LoomAI does not currently use.

## What `0.8.0` Adds For LoomAI

LoomAI gains reusable contracts for a governed document lifecycle:

- side-effect-free `DocumentIngestionPlan` creation;
- content-free `DocumentIngestionManifest` persistence;
- Spring AI text and JSON readers behind an AI Fabric trusted-resource policy;
- optional Spring AI transformers and token splitting;
- bounded, allowlist-only parser and application metadata;
- protected tenant, visibility, source, version, chunk, and fingerprint metadata;
- deterministic `aidoc-*` chunk entity IDs;
- canonical `AIIndexDocument` queue work through `DocumentIndexingQueueAdapter`;
- existing durable work-status reconciliation through `IndexingWorkQuery`; and
- exact, payload-free delete work generated from persisted manifests.

These contracts make document ingestion provider-neutral. They continue to use LoomAI's selected
embedding and AI Fabric vector providers.

## What LoomAI Still Owns

AI Fabric does not become LoomAI's document-management system. LoomAI continues to own:

- authenticated tenant and deployment context;
- upload, connector, object-store, or source-repository access;
- malware scanning and file acceptance policy;
- source records and binary/text storage;
- content-free manifest and work-ID persistence;
- activation, replacement, retry, and operator state;
- quotas, scheduling, approvals, retention, and audit history;
- retrieval authorization and response projection; and
- UI/API behavior.

Spring AI owns reader, transformer, and splitter mechanics. AI Fabric owns the safe bridge from
those documents to its existing indexing lifecycle.

## Existing LoomAI Flows That Must Not Change

The following remain valid and should be regression-tested, not rewritten:

- annotation/config-driven entity Data Sync;
- product, policy, review, and other structured record indexing;
- marketplace packaged, SQL, and folder dataset synchronization;
- Shopify catalog and policy synchronization;
- vectorization runner checkpoints and failure buckets;
- runtime indexing admin/status surfaces;
- chat, RAG, specialists, chains, actions, receipts, and reviews.

`AIIndexDocument` remains the canonical indexing payload. The document bridge does not introduce a
second queue or vector lifecycle.

## Intentional Compatibility Break

The pre-0.8 Spring AI document helper was experimental. `0.8.0` intentionally removes direct
adapter-owned queue submission and changes document identity and metadata:

- replace `SpringAiDocumentIndexingAdapter.enqueue(...)` with `plan(...)` followed by
  `DocumentIndexingQueueAdapter.submit(...)`;
- replace `springai-*` IDs with deterministic `aidoc-*` IDs;
- replace `_springAi*` metadata with protected `_aiDocument*` metadata; and
- persist a content-free manifest before relying on exact replacement or deletion.

LoomAI has no current calls to the old adapter, so this is an adoption rule rather than an immediate
source migration.

## Required Dependency Shape For Document Adoption

The base upgrade only changes the BOM version. A LoomAI module that adopts the Spring AI document
bridge should declare the indexing module and Spring AI document classes explicitly:

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

`spring-ai-commons` is optional in `ai-fabric-indexing`; consumers must not depend on an unrelated
provider module to add it accidentally.

## Configuration Boundary

Document support is conditional on the normal indexing, embeddings, and vector prerequisites plus:

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
        allowed-application-keys: []
        warn-on-drop: true
```

LoomAI should define a narrow application metadata allowlist for each product workflow. Tenant,
visibility, source identity, and lifecycle keys must come from trusted server context rather than
uploaded content, connector metadata, or client requests.

## Operational Contract

Queue acceptance is not indexing completion. LoomAI must persist returned work IDs and reconcile
them through `IndexingWorkQuery` before activating a source version.

Replacement is new-first:

```text
old version ACTIVE
  -> prepare candidate plan
  -> persist DRAFT content-free manifest
  -> submit candidate chunks
  -> reconcile every work ID to successful terminal state
  -> activate candidate
  -> supersede old manifest
  -> submit exact old-manifest deletes
  -> reconcile every delete
  -> mark old manifest DELETED
```

If preparation, embedding, queueing, or vector persistence fails, retain the old active manifest.
Never delete old evidence before the candidate succeeds.

## Security Requirements

- Build plans only from authenticated, server-owned source records.
- Use `SpringAiDocumentReaderFactory` with `SpringAiTrustedResourcePolicy`.
- Permit only approved local roots or trusted classpath resources.
- Do not enable remote URL reading; `0.8.0` rejects it by design.
- Derive tenant, deployment, visibility, entity type, source ID, and source version server-side.
- Keep complete plans in memory/server scope; they contain chunk content.
- Persist only content-free manifests and bounded failure evidence.
- Apply tenant and deployment authorization again during retrieval.
- Treat parser, embedding, queue, and vector failures as visible failures. Do not substitute fallback
  success.

## Deliberately Deferred

`0.8.0` does not provide PDF/OCR, remote crawling, managed connectors, framework source storage,
remote plan submission, a document-specific Data Sync mapper, shared REST preview DTOs, or a generic
Platform reindex state machine. LoomAI should not advertise or implement around those as if they
were included.

Adopt an additional reader or connector only when a real corpus requires it and its resource,
metadata, tenant, and deletion policies are proven.

## Required Release Gates

Gate A must prove:

- all AI Fabric dependencies resolve to `0.8.0` from Maven Central;
- existing structured Data Sync and vectorization behavior is unchanged;
- runtime indexing work queries still reconcile correctly;
- chat, RAG, specialist, action, receipt, and review tests pass;
- tenant and deployment isolation still pass; and
- deployed version/commit readback reports the intended immutable builds.

Gate B must additionally prove:

- preview creates no queue or vector side effects;
- text and JSON plans stay within configured bounds;
- untrusted resources, metadata, and oversized inputs fail closed;
- queue acceptance is shown separately from completion;
- Tenant A evidence is not visible to Tenant B;
- a failed candidate leaves the old active version searchable;
- successful replacement activates the new version before exact old deletion; and
- source deletion removes every manifest entity ID.

Use the detailed
[LoomAI 0.8 document-indexing adoption runbook](../Framework-Dev-Guides/retrieval-vectorization/LOOMAI_AI_FABRIC_0_8_DOCUMENT_INDEXING_MIGRATION_RUNBOOK.md)
for execution.
