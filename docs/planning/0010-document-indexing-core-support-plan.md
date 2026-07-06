# AI Fabric Document Indexing Core Support Plan

Status: draft for implementation planning

Date: 2026-07-05

Owner: AI Fabric framework

Related:

- `docs/planning/0003-spring-ai-capability-adoption-plan.md`
- `docs/planning/0007-document-ingestion-workbench-proposal.md`
- `docs/Framework-Dev-Guides/retrieval-vectorization/RAG_INDEXING_LIFECYCLE_GUIDE.md`
- `docs/Framework-Dev-Guides/retrieval-vectorization/DATA_SYNC_PUSH_API_GUIDE.md`
- `docs/Framework-Dev-Guides/retrieval-vectorization/PLATFORM_VECTORIZATION_AND_TENANT_VERIFICATION_GUIDE.md`

## Purpose

Define how AI Fabric should support the core need for document indexing without reinventing document
ETL features that Spring AI already provides.

The goal is not to build a second document framework. The goal is to make document indexing easy,
safe, observable, and opinionated for AI Fabric applications:

- use Spring AI for commodity document parsing, document abstractions, splitting, transformation, and
  future ETL helpers;
- use AI Fabric for trust policy, indexing contracts, vector-space validation, queueing, embedding,
  vector persistence, RAG retrieval, source evidence, deletion/reindex semantics, tenant/security
  boundaries, and release verification;
- keep product/operator workflows such as upload UI, source credentials, approvals, and managed
  connectors in real apps or Platform, not in the open-source core.

## Decision Summary

AI Fabric should support document indexing through a small, framework-level document indexing layer
on top of Spring AI document primitives.

The framework should provide:

1. A stable document ingestion plan contract.
2. A preview path that uses the same parsing/chunking/bounds as indexing but performs no vector
   writes.
3. Opinionated metadata normalization for source, document, chunk, tenant, visibility, version, and
   attribution fields.
4. Deterministic chunk identity generation.
5. Manifest-friendly output for application-owned reindex/delete workflows.
6. Conversion to existing AI Fabric indexing paths:
   - internal queue mode through `IndexingQueueService`;
   - external/runtime mode through Data Sync-compatible upsert/delete records.
7. Optional reader factory extensions only where Spring AI provides the reader and the dependency can
   stay optional.
8. Release gates proving document preview, indexing, retrieval evidence, reindex, and delete.

The framework should not own:

- general document management;
- remote user-supplied URL crawling;
- source connector credentials;
- operator UI;
- persistent file storage;
- product approvals;
- billing/package limits;
- Platform rollout workflows;
- Spring AI vector store replacement for AI Fabric vector providers.

## Strategy Alignment

This plan follows the AI Fabric framework philosophy:

- **Greenfield:** add clean, small contracts rather than compatibility layers around accidental app
  behavior.
- **Fail closed:** reject untrusted resources, unknown vector spaces, oversized content, unsafe
  metadata, and tenant ambiguity before indexing.
- **Respect existing foundations:** Spring AI now provides useful document ETL primitives; AI Fabric
  should build on them instead of cloning readers, splitters, and transformers.
- **Framework burden:** expose portable contracts and predictable semantics that application builders
  can depend on.
- **Security:** never silently index content from ambiguous source locations or leak paths, URLs,
  tokens, prompts, completions, or raw credentials into vector metadata.

## Spring AI Versus AI Fabric Ownership

| Concern | Use Spring AI | AI Fabric Adds |
| --- | --- | --- |
| Document abstraction | `org.springframework.ai.document.Document` | AI Fabric maps documents into `IndexingRequest` and Data Sync records. |
| Text/JSON readers | Spring AI reader implementations | Trusted resource policy before reader construction. |
| Markdown/HTML/PDF/Tika readers | Spring AI where available | Optional, dependency-light factory methods and fail-closed dependency behavior. |
| Chunking/splitting | Spring AI `DocumentTransformer`, `TokenTextSplitter`, future splitters | Bounds, deterministic chunk ids, source/chunk metadata, manifest output. |
| Metadata from parser | Spring AI document metadata | Allowlist, normalization, truncation, sensitive-key dropping. |
| Embeddings | Spring AI embedding provider behind AI Fabric provider integration where configured | AI Fabric provider selection, fallback, diagnostics, embedding dimension/vector-store compatibility. |
| Vector storage | Existing AI Fabric vector providers | Store/search/update/delete/count lifecycle, provider parity tests, tenant filters. |
| RAG | AI Fabric RAG | Retrieval evidence, attribution, filters, generation handoff. |
| Evaluation | Spring AI evaluators where helpful | AI Fabric result shape, redaction, release thresholds. |
| Governance | N/A | Tenant access, delete/reindex semantics, audit-friendly result contracts. |

## Current Foundation

AI Fabric already has the lower-level pieces:

- `SpringAiDocumentReaderFactory`
  - creates trusted Spring AI text and JSON readers;
  - validates resources with `SpringAiTrustedResourcePolicy`;
  - rejects remote URL resources.
- `SpringAiDocumentIndexingAdapter`
  - reads Spring AI documents;
  - applies Spring AI transformers;
  - uses `TokenTextSplitter`;
  - converts chunks into `IndexingRequest`;
  - enqueues through `IndexingQueueService`;
  - validates entity/vector-space configuration;
  - bounds content and metadata;
  - drops sensitive metadata keys.
- `SpringAiDocumentIndexingOptions`
  - carries source identity, target entity type, splitting, bounds, metadata, and retry choices.
- `IndexingQueueService`
  - supports queued indexing, retry, dead-letter, and worker processing.
- Data Sync API
  - supports `/vector-spaces`, `/upsert`, `/delete`, and `/batch` for external push ingestion.
- RAG and vector providers
  - support embedding, vector storage, search, metadata filters, update, delete, and source evidence.

The gap is not "can AI Fabric index document text?" The gap is a stable, explicit document indexing
contract that applications and Platform can use consistently.

## Target Core Capability

AI Fabric should offer a document indexing support layer with this shape:

```text
Trusted resource or Spring AI Document list
  -> Spring AI reader/transformers
  -> AI Fabric document ingestion plan
  -> safe preview, manifest, and deterministic chunk ids
  -> AI Fabric indexing queue or Data Sync batch
  -> vector store
  -> RAG retrieval with source evidence
  -> deterministic reindex/delete by manifest
```

The central idea is: **Spring AI produces and transforms documents; AI Fabric decides whether, where,
and how they become governed searchable knowledge.**

## Proposed Framework Contracts

### 1. `DocumentIngestionPlan`

Add a framework DTO returned by the document indexing adapter.

Suggested fields:

```text
planId
sourceId
sourceName
sourceVersion
entityType
tenantId
documentCount
chunkCount
totalContentLength
droppedMetadataCount
warnings
chunks[]
indexingRequests[]
dataSyncOperations[]
```

Each chunk should include:

```text
chunkEntityId
documentId
chunkIndex
chunkCount
contentPreview
contentLength
contentFingerprint
metadata
warnings
```

Rules:

- `contentPreview` is bounded and safe for UI preview.
- `indexingRequests` may include full content for server-side queueing.
- `dataSyncOperations` are optional and generated only when requested.
- sensitive metadata is dropped before the plan is returned.
- plan generation performs no vector write.

### 2. `DocumentIngestionManifest`

Add a small manifest contract that applications can persist.

Suggested fields:

```text
sourceId
sourceVersion
entityType
tenantId
chunkEntityIds[]
contentFingerprints[]
createdAt
metadataSummary
```

The manifest allows applications to:

- delete exact chunks by entity id;
- reindex without broad metadata deletes;
- prove how many chunks belong to a source version;
- compare versions before replacing searchable content.

AI Fabric should provide the DTO and helper methods. Applications or Platform should own manifest
storage.

### 3. `DocumentMetadataNormalizer`

Add an opinionated metadata normalizer for document chunks.

Default allowed framework keys:

```text
sourceId
sourceName
sourceType
sourceVersion
documentId
documentTitle
documentSection
chunkIndex
chunkCount
contentFingerprint
tenantId
visibility
locale
createdAt
updatedAt
knowledgeSourceAttributionLabel
```

Default blocked key families:

```text
token
secret
password
credential
authorization
cookie
url
uri
path
file
prompt
completion
embedding
vector
raw
```

Behavior:

- sanitize metadata keys to a stable case/style;
- reject or drop unsupported nested values;
- bound count, key length, and value length;
- preserve user-safe source labels;
- record dropped metadata count and warning codes;
- never put raw file paths or external URLs into vector metadata.

### 4. `DocumentChunkIdentity`

Add a deterministic chunk id helper.

Recommended formula:

```text
{sourceId}:{sourceVersion}:{documentId}:{chunkIndex}:{contentFingerprint-short}
```

or a normalized hash over those values when compact IDs are required.

Rules:

- same source/version/document/chunk content produces the same id;
- changed content produces a different id unless the caller explicitly opts into stable logical ids;
- IDs must be safe for every supported vector provider;
- helper should expose both readable and hashed variants.

### 5. `DocumentIndexingPreview`

Add a first-class preview method to `SpringAiDocumentIndexingAdapter`.

Possible API:

```java
DocumentIngestionPlan preview(DocumentReader reader, SpringAiDocumentIndexingOptions options);
DocumentIngestionPlan preview(List<Document> documents, SpringAiDocumentIndexingOptions options);
```

Preview should:

- run the same reader, transformer, chunking, metadata, and bounds path as indexing;
- validate entity type and indexability;
- return chunk previews and warnings;
- not call `IndexingQueueService`;
- not call embeddings;
- not write vectors.

This gives apps and Platform a safe approval screen before indexing.

### 6. `DocumentIndexingSubmission`

Add helper methods that submit a previously generated plan.

Possible API:

```java
List<IndexingQueueEntry> enqueue(DocumentIngestionPlan plan);
DataSyncBatchRequest toDataSyncBatch(DocumentIngestionPlan plan);
DocumentIngestionManifest toManifest(DocumentIngestionPlan plan);
```

Rules:

- submission should revalidate the plan against current entity config;
- queued mode should use existing `IndexingQueueService`;
- Data Sync mode should generate records that external runtimes already understand;
- AI Fabric should not persist source files or manifests by default.

### 7. Manifest Delete Helper

Add a helper that turns a manifest into explicit delete operations.

Possible API:

```java
List<DataSyncDeleteRequest> toDeleteRequests(DocumentIngestionManifest manifest);
List<IndexingDeleteRequest> toIndexingDeleteRequests(DocumentIngestionManifest manifest);
```

Rules:

- delete by explicit chunk ids;
- avoid broad delete-by-metadata as the primary path;
- return deletion evidence, including expected count and attempted ids;
- fail closed when tenant/entity type is missing.

If the existing indexing layer does not yet have a first-class delete request, use Data Sync delete
for external/runtime mode and document the internal gap as a follow-up.

## Reader Support Plan

AI Fabric should expose only reader factory methods that wrap Spring AI readers and preserve the trust
policy.

### Phase 1 Readers

Keep:

- text;
- JSON.

These are already present and should remain the baseline.

### Phase 2 Readers

Add only if Spring AI provides stable reader support and the dependency can stay optional:

- Markdown;
- HTML.

Rules:

- no core dependency bloat;
- reader methods are conditional on classpath;
- missing reader dependency returns a clear unsupported-reader error;
- tests prove trust policy still runs before reader creation.

### Phase 3 Readers

Evaluate:

- PDF;
- Tika-backed document reading;
- OCR-derived text only if a separate AI Fabric multimodal/OCR module exists.

Rules:

- keep parser dependencies app-scoped unless broadly justified;
- bound page count and extracted content length;
- never allow parser-provided file paths or URLs into metadata;
- avoid claiming production PDF ingestion until tested with real documents and failure cases.

## Configuration Model

Add document-indexing properties under the existing indexing namespace.

Example:

```yaml
ai:
  infrastructure:
    indexing:
      documents:
        enabled: true
        trusted-roots:
          - /srv/ai-fabric/trusted-documents
        max-documents-per-plan: 100
        max-chunks-per-plan: 500
        max-content-length-per-chunk: 10000
        max-total-content-length: 1000000
        max-metadata-entries: 32
        max-metadata-value-length: 512
        default-splitter:
          type: token-text
          chunk-size: 800
          min-chunk-size: 200
          min-chunk-length-to-embed: 5
          max-num-chunks: 500
        metadata:
          allow-application-keys: true
          dropped-key-warning: true
        preview:
          max-preview-chars-per-chunk: 500
          include-full-content: false
```

Configuration principles:

- defaults should be safe for local apps and demos;
- production apps should explicitly configure trusted roots and bounds;
- no remote URL ingestion by default;
- application-specific metadata keys are allowed only after framework normalization and bounds.

## Internal Queue Mode

Use this when the application runs AI Fabric indexing in-process.

Flow:

```text
DocumentReader
  -> preview(...)
  -> persist app-owned source/manifest draft
  -> enqueue(plan)
  -> IndexingQueueService
  -> indexing workers
  -> AI Fabric embedding/vector/RAG
```

AI Fabric should make this easy with `DocumentIngestionPlan` and `enqueue(plan)`.

Applications still own:

- source storage;
- upload approvals;
- job status UI;
- manifest persistence;
- user/admin permissions.

## Data Sync Mode

Use this when the document workbench or Platform service is separate from the target runtime.

Flow:

```text
DocumentReader
  -> preview(...)
  -> approve
  -> toDataSyncBatch(plan)
  -> POST /api/ai/data-sync/batch
  -> target runtime embeds and stores
```

AI Fabric should provide the conversion helper. The Data Sync API should remain the runtime boundary.

Important behavior:

- target runtime still validates vector space and auth;
- target runtime still owns embeddings and vector writes;
- client-side plan generation does not bypass runtime policy;
- batch responses become source indexing evidence for the application.

## Reindex Semantics

Use two-phase reindexing:

1. Generate a new plan for the new source version.
2. Index the new chunk ids.
3. Verify success count and retrieval/readiness evidence.
4. Delete old chunk ids from the previous manifest.
5. Mark the new manifest active and old manifest superseded.

AI Fabric should provide helper contracts and documentation. Applications or Platform should own the
transaction boundary because they own source and manifest storage.

Do not delete old chunks before the new version is successfully indexed.

## Delete Semantics

Delete should be based on the stored manifest:

```text
sourceId + sourceVersion
  -> manifest chunkEntityIds
  -> explicit delete requests
  -> deletion evidence
  -> retrieval smoke confirms no old source evidence
```

Avoid relying on broad metadata filters as the primary delete path. Metadata-filter delete can be a
future provider capability, but the stable release path should use explicit chunk ids.

## Security Requirements

Document indexing must fail closed for:

- missing tenant where tenant isolation is enabled;
- unknown entity/vector-space;
- non-indexable entity/vector-space;
- remote URL resources;
- file resources outside trusted roots;
- unsupported file type;
- oversized file/content/chunk count;
- parser failure;
- unsafe metadata;
- vector provider write failure;
- partial delete without evidence.

Document indexing must not:

- fetch arbitrary URLs from user input;
- include raw local paths in metadata;
- include credentials, tokens, cookies, prompts, completions, embeddings, or provider payloads in
  metadata;
- silently index partial documents after parser errors unless explicitly configured as a warning mode;
- silently filter unauthorized tenant/source content.

## Observability And Evidence

Add or standardize evidence fields in plan, submission, and delete responses:

```text
planId
sourceId
sourceVersion
entityType
tenantId
documentCount
chunkCount
queuedCount
indexedCount
deletedCount
droppedMetadataCount
warningCodes
failureCode
failureMessage
traceId
```

Recommended warning codes:

```text
METADATA_KEY_DROPPED
METADATA_VALUE_TRUNCATED
CONTENT_TRUNCATED
CHUNK_LIMIT_REACHED
UNSUPPORTED_READER_OPTION
PREVIEW_CONTENT_BOUNDED
```

Recommended failure codes:

```text
DOCUMENT_RESOURCE_UNTRUSTED
DOCUMENT_READER_UNSUPPORTED
DOCUMENT_PARSE_FAILED
DOCUMENT_PLAN_LIMIT_EXCEEDED
DOCUMENT_ENTITY_TYPE_UNKNOWN
DOCUMENT_ENTITY_NOT_INDEXABLE
DOCUMENT_TENANT_REQUIRED
DOCUMENT_METADATA_REJECTED
DOCUMENT_INDEXING_QUEUE_FAILED
DOCUMENT_DATASYNC_BATCH_FAILED
DOCUMENT_DELETE_INCOMPLETE
```

## Platform Fit

This core support is useful to Platform, but Platform should consume it rather than move product
workflow into AI Fabric core.

Platform can build:

- source connection setup;
- upload/import UI;
- tenant-aware source registry;
- approval workflow;
- indexing jobs and progress;
- managed vector/runtime selection;
- reindex/delete controls;
- audit and retention;
- release gate dashboards;
- customer/operator evidence views.

AI Fabric should provide:

- document plan contract;
- safe preview;
- metadata normalization;
- chunk identity;
- queue/Data Sync conversion;
- delete/reindex helper contracts;
- retrieval evidence expectations;
- deterministic tests and real-app proof.

This separation keeps AI Fabric open-source and portable while letting Platform become the managed
product experience around it.

## Relationship To `document-ingestion-workbench`

`document-ingestion-workbench` should remain the first real-app proof.

The real app should validate:

- trusted upload;
- preview without vector write;
- index approval;
- manifest persistence;
- RAG question with source evidence;
- two-phase reindex;
- explicit delete by manifest;
- smoke script and CI evidence.

Once the real app proves which helpers are truly reusable, promote the minimal contracts back into
`ai-fabric-indexing`.

## Implementation Phases

### Phase 0: Contract Design

Deliverables:

- document `DocumentIngestionPlan` fields;
- document `DocumentIngestionManifest` fields;
- define warning/failure codes;
- decide whether these DTOs live in `ai-fabric-indexing` or a small document-indexing package inside
  that module;
- add architecture docs to the RAG indexing lifecycle guide.

Acceptance:

- no runtime behavior change;
- docs reviewed against Spring AI adoption ADR;
- no Platform/product workflow added to framework core.

### Phase 1: Preview And Plan

Deliverables:

- add `DocumentIngestionPlan`;
- add `DocumentIngestionChunk`;
- add `DocumentIngestionManifest`;
- add `DocumentMetadataNormalizer`;
- add deterministic chunk id helper;
- add `preview(...)` methods to `SpringAiDocumentIndexingAdapter`;
- update tests for text and JSON readers.

Acceptance:

- preview validates entity type and indexability;
- preview applies the same transformers/bounds as enqueue;
- preview performs no queue, embedding, or vector write;
- unsafe metadata is dropped with warning evidence;
- chunk ids are deterministic;
- unknown/non-indexable vector spaces fail closed.

### Phase 2: Submission Helpers

Deliverables:

- add `enqueue(DocumentIngestionPlan plan)`;
- add `toDataSyncBatch(DocumentIngestionPlan plan)`;
- add `toManifest(DocumentIngestionPlan plan)`;
- add `toDeleteRequests(DocumentIngestionManifest manifest)`;
- document internal queue mode and Data Sync mode.

Acceptance:

- queue mode uses existing `IndexingQueueService`;
- Data Sync batch shape matches existing runtime API;
- manifest contains all chunk ids needed for exact delete;
- submission revalidates entity config;
- delete helper fails closed without tenant/entity/chunk ids.

### Phase 3: Real-App Proof

Deliverables:

- implement or update `examples/real-apps/document-ingestion-workbench`;
- include text and JSON upload/import;
- add source registry and manifest persistence in app-owned storage;
- add preview/index/reindex/delete endpoints;
- add simple RAG smoke question;
- add README and request examples.

Acceptance:

- deterministic smoke uploads a document, previews chunks, indexes, retrieves evidence, deletes, and
  verifies retrieval is gone;
- unsupported file and untrusted path fail closed;
- reindex does not delete old version until new version succeeds.

### Phase 4: Optional Reader Expansion

Deliverables:

- evaluate Spring AI Markdown reader;
- evaluate Spring AI HTML reader;
- evaluate PDF/Tika only if dependency footprint is acceptable;
- add conditional factory methods and tests.

Acceptance:

- reader dependencies remain optional;
- missing reader class produces clear unsupported-reader failure;
- trust policy is enforced before reader creation;
- parser metadata remains sanitized.

### Phase 5: Quality And Evaluation

Deliverables:

- add RAG evidence quality smoke for indexed document chunks;
- optionally wire Spring AI RAG evaluators for release tests;
- publish a scorecard shape for document ingestion quality.

Acceptance:

- test proves at least one expected source id appears in retrieval evidence;
- deletion smoke proves deleted source id no longer appears;
- evaluator output is redacted and bounded;
- quality gate is opt-in and does not slow the default hot path.

## Test Plan

Module tests:

```bash
mvn -B -V --no-transfer-progress -f ai-infrastructure-module/pom.xml -pl ai-fabric-indexing -am test
mvn -B -V --no-transfer-progress -f ai-infrastructure-module/pom.xml -pl ai-fabric-data-sync -am test
mvn -B -V --no-transfer-progress -f ai-infrastructure-module/pom.xml -pl ai-fabric-rag -am test
```

Real-app tests:

```bash
mvn -B -V --no-transfer-progress -f examples/real-apps/pom.xml -pl document-ingestion-workbench -am test
```

Smoke:

```bash
.github/scripts/smoke-document-ingestion-workbench.sh
```

Smoke should verify:

1. boot app in deterministic profile;
2. upload trusted text fixture;
3. preview chunks;
4. approve index;
5. query and confirm source evidence;
6. reindex and confirm new version evidence;
7. delete and confirm source evidence disappears.

## Documentation Updates

Update these docs as the implementation lands:

- `RAG_INDEXING_LIFECYCLE_GUIDE.md`
  - add the plan/preview/manifest/delete lifecycle.
- `DATA_SYNC_PUSH_API_GUIDE.md`
  - add document chunk batch examples.
- `0007-document-ingestion-workbench-proposal.md`
  - point to this plan as the core-support contract.
- `0006-framework-capability-priority-map.md`
  - promote document indexing core support into P1 when implementation starts.
- `examples/real-apps/document-ingestion-workbench/README.md`
  - describe operator flows and smoke commands.

## Open Questions

1. Should `DocumentIngestionPlan` expose full chunk content by default, or should full content require
   a server-only flag?
2. Should chunk ids include `sourceVersion`, or should apps be able to choose stable logical chunk ids
   across source versions?
3. Do we need a first-class internal delete request in `ai-fabric-indexing`, or is Data Sync delete
   enough for the first release?
4. Should metadata normalizer default to allowlist-only, or allow application keys after sanitization?
5. Which Spring AI reader dependencies are stable enough for optional framework factory methods?
6. Should Platform-owned source verification reuse the same manifest DTO directly or wrap it in a
   Platform-specific source run model?

## Recommendation

Proceed in this order:

1. Add the core plan/preview/manifest contracts in `ai-fabric-indexing`.
2. Keep Spring AI as the document ETL foundation.
3. Keep AI Fabric responsible for safety, identity, indexing, vector/RAG, and deletion semantics.
4. Prove the behavior in `document-ingestion-workbench`.
5. Let Platform build the managed operator experience on top of these contracts.

This gives AI Fabric a strong document indexing story without bloating the framework or duplicating
Spring AI.
