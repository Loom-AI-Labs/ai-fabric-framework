# Document Ingestion Workbench Proposal

Status: proposed

Date: 2026-07-01

Owner: AI Fabric framework plus real-app examples

Core support plan: `docs/planning/0010-document-indexing-core-support-plan.md`

## Summary

Build a real-app proof called `document-ingestion-workbench` that demonstrates how AI Fabric ingests
trusted documents, chunks and normalizes them, indexes them into configured vector spaces, retrieves
them with RAG evidence, and safely reindexes or deletes source material.

This should be an app on top of AI Fabric, not a new core framework product surface. AI Fabric already
has the main primitives: indexing queue/workers, Data Sync ingestion API, vector storage, metadata
filters, RAG, deletion/governance paths, and an optional Spring AI document bridge. The workbench
would assemble those primitives into a visible, end-to-end ingestion scenario.

## Decision

Proceed as a P2 real-app proposal.

Do not position this as an already-shipped framework capability. The current framework supports the
lower-level ingestion pieces, but there is no packaged workbench UI/API yet.

## Why This Fits AI Fabric

AI Fabric is the runtime layer for AI-enabling business systems. A document ingestion workbench proves
that the framework is not limited to database entities like products, orders, reviews, or support
messages. It shows a common enterprise path:

- upload or import trusted source documents;
- parse and normalize content;
- chunk content with stable metadata;
- index into AI Fabric vector spaces;
- answer questions with source evidence;
- update/reindex/delete source documents with governance evidence.

The workbench owns source management and operator UX. AI Fabric owns ingestion contracts, indexing,
retrieval, RAG, access control, vector lifecycle, and governance behavior.

## Current Code Evidence

| Capability | Evidence |
| --- | --- |
| Real-app gap already documented | `docs/planning/0005-real-app-use-case-expansion-plan.md` lists "Document ingestion beyond simple DB text" and proposes `document-ingestion-workbench`. |
| Optional Spring AI document bridge | `ai-fabric-indexing/pom.xml` depends on optional `org.springframework.ai:spring-ai-commons`. |
| Auto-configured document bridge | `AIIndexingAutoConfiguration.SpringAiDocumentIndexingConfiguration` registers `SpringAiDocumentIndexingAdapter` and `SpringAiDocumentReaderFactory` when Spring AI `Document` is on the classpath. |
| Trusted reader creation | `SpringAiDocumentReaderFactory` creates Spring AI `TextReader` and `JsonReader` only after `SpringAiTrustedResourcePolicy` validation. |
| Trust boundary | `SpringAiTrustedResourcePolicy` rejects remote URL resources, requires trusted roots for files, and supports controlled classpath/in-memory resources. |
| Chunking and transformers | `SpringAiDocumentIndexingAdapter` applies Spring AI `DocumentTransformer`s and `TokenTextSplitter`. |
| AI Fabric indexing conversion | `SpringAiDocumentIndexingAdapter` converts Spring AI `Document`s into `IndexingRequest`s and can enqueue them through `IndexingQueueService`. |
| Bounds and metadata safety | `SpringAiDocumentIndexingOptions` bounds chunk count, content length, metadata count, metadata value length, and retry behavior. `SpringAiDocumentIndexingAdapter` drops sensitive metadata keys such as token, secret, URL, path, prompt, and completion. |
| Entity/vector-space validation | `SpringAiDocumentIndexingAdapter` rejects unknown or non-indexable entity types before enqueueing. |
| Push ingestion API | `DataSyncController` exposes `/vector-spaces`, `/upsert`, `/delete`, and `/batch` under `ai.data-sync.base-path`. |
| Data Sync store/delete path | `DataSyncService` validates vector space, checks access, generates embeddings, stores vectors, deletes vectors, and enforces max batch size. |
| Existing tests | `SpringAiDocumentReaderFactoryTest`, `SpringAiDocumentIndexingAdapterTest`, and `AIIndexingAutoConfigurationTest` already cover reader creation, adapter conversion, metadata safety, entity validation, queueing, and auto-configuration. |

## What AI Fabric Already Supports

### 1. Trusted Document To Indexing Queue

Current flow:

```text
Spring Resource
  -> SpringAiTrustedResourcePolicy
  -> SpringAiDocumentReaderFactory
  -> Spring AI DocumentReader
  -> SpringAiDocumentIndexingAdapter
  -> IndexingRequest
  -> IndexingQueueService
  -> Indexing workers
  -> AI Fabric vector/RAG lifecycle
```

This is the cleanest internal path for the workbench when it runs inside a Spring Boot app using
AI Fabric indexing.

### 2. Push-Based Ingestion API

Current flow:

```text
Workbench or external source system
  -> POST /api/ai/data-sync/upsert
  -> DataSyncService
  -> AIEmbeddingService
  -> VectorManagementService.storeVector
```

This is useful when the workbench is a separate service or when a platform/customer app wants to push
already-normalized chunk records into AI Fabric runtime.

### 3. Delete/Reindex Building Blocks

AI Fabric can delete by vector space and entity id through Data Sync delete. The workbench must keep a
source manifest that maps a source document/version to the generated chunk entity ids. That manifest
lets reindex and delete run as deterministic batch operations instead of relying on broad metadata
delete behavior.

## What The Workbench Must Add

The workbench should add product/application behavior around the framework primitives:

1. Source registry:
   - source id;
   - tenant/customer id;
   - source type;
   - filename/title;
   - content hash;
   - version;
   - status;
   - uploaded by;
   - timestamps.

2. Chunk manifest:
   - source id;
   - source version;
   - chunk entity id;
   - chunk index;
   - chunk count;
   - content fingerprint;
   - metadata snapshot.

3. Ingestion jobs:
   - job id;
   - source id;
   - operation: `CREATE`, `REINDEX`, `DELETE`;
   - status: `PENDING`, `RUNNING`, `COMPLETED`, `FAILED`, `PARTIAL`;
   - error code/message;
   - counts: read docs, chunks, queued, indexed, deleted, failed.

4. Upload/import trust pipeline:
   - accept file upload or trusted local/object-store reference;
   - persist into a trusted staging root;
   - reject direct remote URL ingestion;
   - enforce file size/type limits;
   - optional malware/PII scan before indexing.

5. Operator API/UI:
   - upload/import;
   - preview parsed chunks;
   - approve/index;
   - inspect status;
   - reindex;
   - delete source;
   - ask test questions and inspect RAG evidence.

## Spring AI Usage

Use Spring AI for ETL helpers only.

Use now:

- `org.springframework.ai.document.Document`
- `org.springframework.ai.document.DocumentReader`
- `org.springframework.ai.document.DocumentTransformer`
- `org.springframework.ai.reader.TextReader`
- `org.springframework.ai.reader.JsonReader`
- `org.springframework.ai.transformer.splitter.TokenTextSplitter`

Evaluate/add where available and compatible with Spring AI 2.x:

- Markdown reader support;
- HTML reader support;
- PDF/Tika reader support;
- token-aware splitters beyond the current `TokenTextSplitter`;
- metadata-enrichment transformers, if they can run without leaking path/URL/secrets into AI Fabric metadata.

Do not use Spring AI here for:

- vector store replacement;
- RAG policy replacement;
- auth/governance decisions;
- remote URL fetching from user input;
- unbounded parsing/chunking.

Those remain AI Fabric or workbench responsibilities.

## Proposed Architecture

```text
Browser/Admin
  -> Document Ingestion Workbench API
     -> SourceRegistryService
     -> TrustedDocumentStorage
     -> DocumentParsingService
        -> SpringAiDocumentReaderFactory
        -> Spring AI readers/transformers
     -> DocumentChunkPlanningService
        -> SpringAiDocumentIndexingAdapter.toIndexingRequests(...)
     -> IngestionJobService
        -> IndexingQueueService or Data Sync batch
     -> AI Fabric vector/RAG runtime
```

Recommended package shape for the real app:

```text
examples/real-apps/document-ingestion-workbench
  src/main/java/.../documents/domain
  src/main/java/.../documents/storage
  src/main/java/.../documents/ingestion
  src/main/java/.../documents/web
  src/main/resources/ai-entity-config.yml
  src/test/java/.../documents
  README.md
  requests/
```

## AI Entity Model

Add a `knowledge_document_chunk` or `kb` vector space in the app config.

Example:

```yaml
ai:
  entities:
    kb:
      indexable: true
      searchable-fields:
        - content
        - title
        - section
      metadata-fields:
        - sourceId
        - sourceName
        - sourceVersion
        - documentId
        - chunkIndex
        - chunkCount
        - tenantId
        - visibility
        - contentFingerprint
```

The source manifest should store the generated chunk entity ids because Data Sync delete works by
vector space and id. Reindex should delete the previous version's chunk ids, then enqueue the new
version.

## Request Flows

### Upload And Preview

```text
POST /api/documents/sources
  -> store uploaded file under trusted root
  -> create source row: PENDING
  -> parse with Spring AI reader
  -> split/transform
  -> return preview: chunk count, first chunks, metadata summary, dropped metadata count
```

No vector write happens during preview.

### Approve And Index

```text
POST /api/documents/sources/{sourceId}/index
  -> load source file from trusted root
  -> create Spring AI reader
  -> SpringAiDocumentIndexingAdapter.toIndexingRequests(...)
  -> persist chunk manifest
  -> enqueue requests through IndexingQueueService
  -> mark job RUNNING/COMPLETED according to queue result
```

### External Runtime Push Mode

```text
Workbench service
  -> parse/chunk locally
  -> POST /api/ai/data-sync/batch
  -> AI Fabric runtime validates vector space/access
  -> runtime embeds and stores chunks
```

Use this mode when the workbench is deployed separately from the AI Fabric runtime.

### Reindex

```text
POST /api/documents/sources/{sourceId}/reindex
  -> parse new version
  -> enqueue/index new chunks
  -> if new version succeeds, batch delete old chunk ids
  -> mark old version superseded
```

Use a two-phase approach so a failed new ingestion does not erase the last searchable version.

### Delete

```text
DELETE /api/documents/sources/{sourceId}
  -> load chunk manifest
  -> batch delete each chunk entity id
  -> mark source DELETED
  -> return deletion evidence
```

## Framework Enhancements To Consider

The app can start with current code. These small framework additions would make the feature cleaner:

1. `DocumentIngestionPlan`
   - A framework DTO returned by `SpringAiDocumentIndexingAdapter` containing:
     - source id;
     - chunk count;
     - indexing requests;
     - generated chunk entity ids;
     - dropped metadata count.

2. `SpringAiDocumentIndexingAdapter.preview(...)`
   - Same parsing/chunking/bounds as enqueue, but returns a safe preview without queueing.

3. Reader factory extensions
   - Add methods only when the corresponding Spring AI reader dependency is present:
     - `markdownReader(...)`;
     - `htmlReader(...)`;
     - `pdfReader(...)` or `tikaReader(...)`.
   - Preserve `SpringAiTrustedResourcePolicy` before reader construction.

4. Source-level deletion helper
   - Do not add broad vector-store delete-by-metadata as the primary path.
   - Prefer a helper that consumes a stored chunk manifest and issues deterministic delete requests.

5. Metadata schema normalizer
   - Centralize safe metadata keys for document chunks:
     - `sourceId`, `sourceName`, `sourceVersion`, `documentId`, `chunkIndex`, `chunkCount`,
       `tenantId`, `visibility`, `contentFingerprint`.

## Implementation Plan

### Phase 1: Minimal Workbench Real App

Create `examples/real-apps/document-ingestion-workbench`.

Scope:

- text and JSON ingestion only;
- local trusted upload directory;
- source registry in H2/JPA;
- chunk manifest in H2/JPA;
- preview endpoint;
- index endpoint through `SpringAiDocumentIndexingAdapter`;
- delete endpoint using stored chunk ids;
- simple ask endpoint or instructions to use existing chat/RAG runtime.

Tests:

- upload rejects unsupported extension;
- upload stores under trusted root;
- preview does not enqueue;
- index enqueues stable chunk ids;
- delete uses stored chunk ids;
- metadata does not include URL/path/secret-like keys;
- unknown vector space fails closed;
- non-indexable vector space fails closed.

### Phase 2: Richer Formats And Data Sync Mode

Add:

- Markdown reader if Spring AI reader support is available;
- HTML reader if Spring AI reader support is available;
- PDF/Tika reader if Spring AI reader support is available and dependency footprint is acceptable;
- Data Sync batch mode for separate deployment;
- status page showing job lifecycle and failures;
- RAG evidence smoke against indexed documents.

Tests:

- format-specific parser tests;
- Data Sync batch request generation;
- batch size and max content failures;
- reindex preserves old chunks when new parse/index fails;
- delete returns evidence for all chunk ids.

### Phase 3: Governance And Enterprise Controls

Add:

- tenant-aware source isolation;
- role-limited upload/index/delete;
- optional PII scan before indexing;
- retention cleanup integration;
- export/import of source manifest without raw file content;
- audit events for every source operation.

Tests:

- unauthorized tenant cannot read/delete another tenant's source;
- PII-heavy content is blocked or masked according to policy;
- retention delete removes source manifest and vector chunks;
- audit log contains stable ids, not raw document content.

## Release Gates

Minimum release gate for Phase 1:

```bash
mvn -B -V --no-transfer-progress -f ai-infrastructure-module/pom.xml -pl ai-fabric-indexing -am test
mvn -B -V --no-transfer-progress -f examples/real-apps/pom.xml -pl document-ingestion-workbench -am test
```

Add a deterministic smoke script:

```bash
.github/scripts/smoke-document-ingestion-workbench.sh
```

Smoke should:

1. boot the workbench with local vector/embedding test mode;
2. upload a small policy text file;
3. preview chunks;
4. approve indexing;
5. ask a RAG question and verify evidence contains the source id;
6. delete the source;
7. verify the deleted content is no longer retrievable.

## Risks And Guardrails

| Risk | Guardrail |
| --- | --- |
| User-supplied remote URL ingestion becomes SSRF or data exfiltration path | Keep `SpringAiTrustedResourcePolicy` as the mandatory entry point; reject remote URLs. |
| File uploads leak paths, URLs, tokens, or prompts into vector metadata | Keep metadata sanitization and add workbench-level metadata allowlist. |
| Reindex deletes old searchable data before new indexing succeeds | Use two-phase reindex: index new version first, then delete old chunks. |
| Source-level delete is incomplete because chunks are generated ids | Persist a chunk manifest and delete by explicit chunk entity ids. |
| Spring AI parser dependencies expand the framework footprint | Keep reader dependencies optional and app-scoped unless a reader becomes broadly useful. |
| Workbench becomes a second RAG framework | Use Spring AI for ETL only; use AI Fabric for indexing, vector lifecycle, RAG, auth, and governance. |

## Recommendation

Build this as a real app first. It is a strong proof point for AI Fabric because it demonstrates a
complete enterprise knowledge-base lifecycle while reusing existing framework capabilities. Only
promote small generic helpers back into `ai-fabric-indexing` after the app proves they are reusable.
