# AI Fabric Document Indexing Core Support Plan

Status: reduced core implemented for AI Fabric 0.8.0; later phases are demand-gated

Original date: 2026-07-05

Last updated: 2026-09-21

Target release: AI Fabric 0.8.0 reduced core

Implementation baseline:

- AI Fabric 0.7.1
- Java 21
- Spring Boot 4.1.0
- Spring AI 2.0.0

Owner: AI Fabric framework

Related:

- `docs/planning/0003-spring-ai-capability-adoption-plan.md`
- `docs/planning/0007-document-ingestion-workbench-proposal.md`
- `docs/reviews/0.4.0-lifecycle-refactor-assessment.md`
- `docs/Framework-Dev-Guides/retrieval-vectorization/RAG_INDEXING_LIFECYCLE_GUIDE.md`
- `docs/Framework-Dev-Guides/retrieval-vectorization/DATA_SYNC_PUSH_API_GUIDE.md`
- `docs/Framework-Dev-Guides/retrieval-vectorization/PLATFORM_VECTORIZATION_AND_TENANT_VERIFICATION_GUIDE.md`

## Executive Decision

AI Fabric will provide a small governed document-indexing bridge on top of Spring AI's document ETL
primitives. It will not become a general document-management or connector platform.

Spring AI owns commodity document concerns:

- `DocumentReader`;
- `Document`;
- `DocumentTransformer`;
- `TokenTextSplitter` and compatible future splitters;
- optional format readers supplied by Spring AI modules.

AI Fabric owns only the cross-provider guarantees that are already part of its indexing and RAG
responsibility:

- trusted-resource validation;
- bounded preparation and chunk planning;
- protected metadata and tenant context;
- deterministic source, version, and chunk identity;
- canonical `AIIndexDocument` projection;
- existing durable queue submission;
- exact manifest-based deletion;
- vector-provider lifecycle;
- RAG source evidence;
- observable failures and release verification.

Applications or LoomAI Platform own source persistence, connector credentials, schedules, lifecycle
state, operator workflows, and reindex orchestration. A shared Data Sync mapping, preview contract,
additional readers, or managed ingestion features will be added only when a concrete second consumer
proves the need.

AI Fabric will not create competing readers, splitters, a second document model, or a Spring AI
`VectorStore` replacement. Spring AI produces and transforms documents; AI Fabric governs how the
prepared chunks enter its existing indexing contract.

Document parsing and chunking are commodity capabilities, not an AI Fabric differentiator. The
AI Fabric advantage is that documents can participate in the same tenant-safe, provider-independent,
exactly deletable knowledge lifecycle as annotated application entities. This is an enabling
capability for RAG and specialists, not a new primary product category.

## Delivery Decision: Now, Later, Or Outside

This table is the scope authority for implementation. A later section must not silently promote a
deferred item into the 0.8 definition of done.

| Capability | Decision | Why now or why deferred |
| --- | --- | --- |
| Spring AI `DocumentReader`, `Document`, transformers, and token splitter | **0.8 now** | Reuses the commodity ETL layer instead of recreating it. |
| Trusted file/classpath/in-memory resource policy | **0.8 now** | Untrusted source access is a security boundary and already has a framework foundation. |
| Text and JSON readers | **0.8 now** | Already supported, small enough to harden, and sufficient to prove the lifecycle. |
| Deterministic source, version, chunk, and entity identity | **0.8 now** | Required for idempotency, safe replacement, exact deletion, and provider parity. |
| Bounded documents, chunks, content, and metadata | **0.8 now** | Prevents accidental resource exhaustion and unsafe provider payloads. |
| Protected and allowlisted metadata, including tenant context | **0.8 now** | Prevents parser metadata from overriding identity or authorization boundaries. |
| Canonical `AIIndexDocument` projection | **0.8 now** | Avoids a parallel indexing payload and preserves the post-0.4 lifecycle. |
| Server-side `DocumentIngestionPlan` | **0.8 now** | Separates deterministic preparation from queue side effects. |
| Minimal `DocumentIngestionManifest` with exact entity ids | **0.8 now** | Exact replacement and deletion cannot safely depend on broad metadata scans. |
| Existing queue submission and work-status evidence | **0.8 now** | Durable infrastructure already exists; reuse it instead of creating another receipt model. |
| Manifest-to-delete `AIIndexDocument` helper | **0.8 now** | Makes deletion provider-independent and idempotent. |
| New-first replacement demonstrated in the workbench | **0.8 now** | Fixes the current delete-first correctness gap while keeping orchestration app-owned. |
| Source/version/chunk retrieval evidence | **0.8 now** | Proves that indexed knowledge can be attributed and removed. |
| Deterministic packaged lifecycle smoke | **0.8 now** | Prevents a public contract that works only in unit tests. |
| Shared framework preview DTOs | **Later, after a second consumer** | The current workbench can safely project its own bounded preview; one UI does not justify a public preview schema. |
| New submission and work-receipt hierarchy | **Deferred indefinitely** | Existing queue entries, work ids, and status APIs already represent submission. A second hierarchy would duplicate them. |
| Data Sync document mapper | **Later, when LoomAI has a real cross-service ingestion path** | It is useful only when one service prepares documents for another runtime; building it now would be speculative API design. |
| Generic reindex workflow/state machine | **Platform/application concern** | `DRAFT`, `INDEXING`, `ACTIVE`, `SUPERSEDED`, and retry policy depend on product operations and persistence. |
| Plan serialization, schema negotiation, and tamper detection | **Later, if plans cross a trust boundary** | In-process immutable plans do not need a remote protocol. Revalidate entity policy before queue submission instead. |
| Strict active-version retrieval filtering | **Later, when a use case requires zero overlap** | New-first replacement may briefly expose two versions; filtering adds query and lifecycle complexity. |
| Markdown and HTML readers | **Later, format-by-format** | Useful, but each format adds parser behavior and security fixtures beyond the core proof. |
| PDF/Tika, OCR, and multimodal extraction | **Later, demand-gated** | High parser, dependency, resource, and adversarial-input cost; not needed to prove AI Fabric governance. |
| Remote URL crawling and managed connectors | **LoomAI Platform or application** | Credentials, network policy, schedules, and source ownership are product responsibilities. |
| Source files/object storage and manifest persistence | **LoomAI Platform or application** | The framework should not introduce storage or become a document-management system. |
| Approval flows, retention, billing, quotas, and operator UI | **LoomAI Platform** | These are managed-product concerns, not reusable indexing contracts. |
| Document quality scorecards and automated RAG evaluation gates | **Later, after lifecycle adoption** | Quality metrics are valuable only after stable sources, queries, and expected-answer datasets exist. |
| Provider-native bulk indexing optimizations | **Later, after parity evidence** | Correctness and provider-neutral behavior must be established before native acceleration. |

## AI Fabric 0.8 Delivery Scope

The reduced first releasable capability includes:

1. A provider-neutral, server-side `DocumentIngestionPlan` containing approved canonical index
   documents.
2. A minimal `DocumentIngestionManifest` containing exact chunk identities but no raw content.
3. Spring AI text and JSON readers behind the existing trusted-resource policy.
4. Spring AI transformer and token-splitter support.
5. Internal submission through the existing `IndexingQueueService` and its existing work records.
6. Exact manifest-based deletion using payload-free canonical `AIIndexDocument` delete work.
7. App-owned two-phase reindex without a searchable-data gap.
8. An app-owned bounded preview in the workbench, derived from the same prepared plan.
9. A real-app and deterministic smoke test covering prepare, preview, index, retrieve, reindex, and
   delete.

Shared preview contracts, Data Sync document mapping, optional readers, remote connectors, quality
evaluation, and managed source workflows are not part of the 0.8 release gate.

## Implementation Evidence

Work Packages 1 through 5 are implemented as of 2026-09-20. The implementation remains an
unreleased `0.8.0` candidate until its immutable Maven artifacts and release tag exist.

Local release evidence:

- `ai-fabric-indexing`: 92 tests pass, including planning, bounds, trust policy, metadata,
  deterministic identity, queue acceptance, exact deletes, and auto-configuration boundaries;
- the provider-only starter context regression passes, proving document beans do not leak into an
  application with search and embeddings disabled;
- `smoke-support`: 13 tests pass;
- `document-ingestion-workbench`: 16 JPA, service, controller, and full-context tests pass;
- the packaged deterministic smoke passes for text and JSON preview, indexing, tenant-scoped
  retrieval evidence, new-first replacement, exact deletion, and fail-closed inputs;
- the complete 36-module framework `clean verify` passes on Java 21 with tests enabled.

The workbench and framework tests intentionally divide proof by ownership. The packaged HTTP smoke
exercises public application behavior. Focused framework tests inject parser metadata, remote URLs,
out-of-root files, queue anomalies, and worker failures that the public upload API does not expose.

## Non-Goals

The framework will not own:

- a document-management system;
- source files or object storage;
- arbitrary remote URL crawling;
- connector credentials;
- malware scanning;
- an operator UI;
- approval workflow persistence;
- billing or package limits;
- Platform rollout orchestration;
- OCR or multimodal extraction in this module;
- direct writes through Spring AI `VectorStore` or `DocumentWriter` that bypass AI Fabric policy.

Applications or LoomAI Platform may build these product capabilities on top of the framework
contracts.

## Philosophy Alignment

This design follows the AI Fabric framework philosophy:

- **Greenfield:** replace the incomplete convenience API with one coherent lifecycle in the same
  release. Do not add deprecated aliases or compatibility wrappers.
- **Use existing intelligence and infrastructure:** use Spring AI's document ETL rather than cloning
  readers and splitters.
- **Fail closed:** reject ambiguous identity, untrusted resources, unknown vector spaces, unsafe
  bounds, authorization failures, and incomplete deletes.
- **Keep policy in AI Fabric:** Spring AI parses content; it does not decide tenant access, metadata
  exposure, vector lifecycle, or reindex safety.
- **Visible failures:** parser, projection, queue, vector, and deletion failures remain
  distinguishable. A future Data Sync path must preserve the same rule. No fallback may report
  successful indexing after a failed path.
- **Framework burden:** exported contracts must be deterministic, immutable, bounded, and usable by
  ordinary Spring Boot applications without adopting a managed Platform.

## Current Code Evidence

The implementation starts from working foundations rather than a blank module.

| Existing capability | Current code | Assessment |
| --- | --- | --- |
| Optional Spring AI dependency | `ai-fabric-indexing/pom.xml` uses optional `spring-ai-commons` | Keep optional. |
| Conditional bridge | `AIIndexingAutoConfiguration.SpringAiDocumentIndexingConfiguration` | Keep conditional on Spring AI `Document`. |
| Trusted readers | `SpringAiDocumentReaderFactory` and `SpringAiTrustedResourcePolicy` | Keep and extend tests. |
| Spring AI ETL | `SpringAiDocumentIndexingAdapter` consumes `DocumentReader`, `Document`, `DocumentTransformer`, and `TokenTextSplitter` | Refactor behind the plan contract. |
| Canonical payload | The adapter already produces `AIIndexDocument` | Keep; remove stale `IndexingRequest` terminology. |
| Durable indexing | `IndexingQueueService` accepts `AIIndexDocument` | Use for internal submission. |
| Push ingestion | `ai-fabric-data-sync` already depends on `ai-fabric-indexing` and exposes batch upsert/delete | Keep as a future integration point; do not add a document mapper until LoomAI proves a cross-service flow. |
| App proof | `examples/real-apps/document-ingestion-workbench` supports text/JSON preview, queueing, manifests, replacement, and delete | Refactor to consume framework contracts. |
| Tests | Adapter, reader factory, auto-configuration, service, and controller tests exist | Preserve and broaden. |

### Confirmed Gaps

The current bridge can parse and queue document chunks. The release-blocking gaps are:

- no framework `DocumentIngestionPlan`;
- no framework manifest contract;
- no shared metadata normalizer or warning model;
- current chunk identity omits source version and content fingerprint;
- current metadata names expose the Spring AI implementation through `_springAi*` keys;
- current workbench reindex deletes old chunks before replacement indexing succeeds;
- no deterministic end-to-end smoke script proves retrieval evidence and deletion.

The following are observed extension opportunities, not release-blocking gaps:

- no shared preview contract distinct from the full server-side plan;
- no Data Sync plan mapper;
- no document-specific submission receipt joining plan ids to queue work ids;
- no optional Markdown, HTML, PDF, OCR, or remote-source integrations.

They remain deferred because the workbench can own its preview, the existing queue already returns
work evidence, and no deployed cross-service document consumer currently requires the other APIs.

## Ownership Boundary

| Concern | Spring AI | AI Fabric | Application or Platform |
| --- | --- | --- | --- |
| Document abstraction | `Document` | Consume only at the ETL adapter boundary | May construct trusted server-side documents |
| Text/JSON parsing | Spring AI readers | Enforce trust before reader construction | Store/upload source safely |
| Markdown/HTML/PDF | Optional Spring AI readers | Conditional adapters and bounds | Choose and package format support |
| Splitting | `DocumentTransformer`, `TokenTextSplitter` | Configure limits and validate output | Select app-specific transformers if needed |
| Parser metadata | Supplies metadata | Normalize, protect, bound, and warn | Declare allowed application keys |
| Chunk identity | None | Deterministic source/version/content identity | Persist returned manifest |
| Index payload | None | Canonical `AIIndexDocument` | Do not construct queue payloads manually |
| Embeddings | Spring AI provider may sit behind AI Fabric provider integration | Provider selection and diagnostics | Configure provider credentials |
| Vector persistence | Not used through Spring AI `VectorStore` | Existing AI Fabric vector providers | Select provider and infrastructure |
| Internal submission | None | Existing indexing queue, workers, retry, and dead letter | Observe completion |
| External submission | None | Existing Data Sync remains available for callers that construct canonical operations; a document-specific mapper is deferred | Provide verified backend identity and mapping until a shared mapper is justified |
| Source lifecycle | None | Plan/manifest/delete helpers | Persist source, manifest, and lifecycle state |
| RAG | Optional Spring AI evaluation helpers | Retrieval, evidence, policy, generation handoff | Present evidence to users |

## Target Architecture

```text
Trusted Resource
  -> SpringAiTrustedResourcePolicy
  -> Spring AI DocumentReader
  -> List<Spring AI Document>
  -> Spring AI DocumentTransformer(s) / TokenTextSplitter
  -> AI Fabric metadata normalization + chunk identity
  -> DocumentIngestionPlan
       |-> application-owned bounded preview (safe for UI/API)
       |-> DocumentIngestionManifest (exact ids, safe for app persistence)
       |-> List<AIIndexDocument> (server-side only)
       |
       +-> existing IndexingQueueService -> workers -> vector provider

Vector provider
  -> AI Fabric RAG retrieval
  -> source/chunk evidence
  -> explicit manifest-driven reindex or delete

Demand-gated future path:

DocumentIngestionPlan
  -> DocumentDataSyncMapper
  -> DataSyncBatchRequest
  -> target runtime -> vector provider
```

## Module And Dependency Design

### `ai-fabric-indexing`

Owns provider-neutral document lifecycle contracts and the Spring AI ETL adapter.

Proposed packages:

```text
ai.fabric.indexing.document.model
  DocumentIngestionPlan
  DocumentIngestionChunk
  DocumentIngestionManifest
  DocumentManifestChunk
  DocumentIngestionWarning

ai.fabric.indexing.document
  DocumentMetadataNormalizer
  DocumentChunkIdentity
  DocumentManifestOperations
  DocumentIndexingQueueAdapter

ai.fabric.indexing.document.springai
  SpringAiDocumentIndexingAdapter
  SpringAiDocumentIndexingOptions
  SpringAiDocumentReaderFactory
  SpringAiTrustedResourcePolicy
```

The model and queue-adapter packages must not expose Spring AI types. Only the `springai` adapter
package may import `org.springframework.ai.document.*`. `DocumentIngestionPlan` and
`DocumentIngestionManifest` are the two public lifecycle concepts. Immutable chunk and warning
records support those concepts but do not create independent lifecycle abstractions.

### `ai-fabric-data-sync` Follow-Up

When LoomAI proves a real cross-service document-preparation use case, this module will own conversion
from an indexing plan/manifest to Data Sync DTOs because it already depends on
`ai-fabric-indexing`.

Proposed package:

```text
ai.fabric.datasync.document
  DocumentDataSyncMapper
```

This package is not part of the 0.8 scope. When implemented, `ai-fabric-indexing` must not depend on
`ai-fabric-data-sync`; doing so would create a module cycle.

### `document-ingestion-workbench`

Owns source files, source records, manifest persistence, approval state, and the two-phase reindex
workflow. App-local duplicate chunk planning and identity logic must be removed after framework
contracts land. Its bounded preview DTO remains app-owned until another consumer demonstrates a
stable shared shape.

## Framework Contracts

The 0.8 code may use records or immutable final classes. Only the plan and manifest are new public
lifecycle contracts. Supporting chunk and warning values may be nested records or small immutable
types. Preview and submission shapes remain outside the public framework contract for this phase.

### `DocumentIngestionPlan`

This is a server-side immutable value object, not a REST response.

```text
planId
sourceId
sourceVersion
sourceName
entityType
tenantId
visibility
createdAt
documentCount
totalContentLength
chunks[]
warnings[]
```

Rules:

- `sourceId`, `sourceVersion`, and `entityType` are required.
- `sourceVersion` is a non-negative `long` and maps directly to
  `AIIndexDocument.sourceVersion`.
- tenant id has no framework-generated default. It is required when the target entity/access policy
  is tenant-scoped.
- `planId` is deterministic from entity type, source identity/version, and ordered chunk
  fingerprints. Replanning unchanged input produces the same id.
- the plan contains approved `AIIndexDocument` instances through its chunks, including full content;
  controllers must return an app-owned bounded preview projection, never serialize the plan.
- constructing a plan performs no queue, embedding, vector, or Data Sync operation.
- collections and metadata are immutable defensive copies.

### `DocumentIngestionChunk`

```text
documentOrdinal
sourceDocumentId
chunkId
chunkIndex
chunkCount
entityId
contentLength
contentFingerprint
indexDocument
warnings[]
```

`indexDocument` is the canonical `AIIndexDocument`. Do not introduce another queue payload.

### App-Owned Preview For 0.8

The workbench must expose a bounded API/UI preview, but the DTO remains application-owned in 0.8.
It is derived from an already validated `DocumentIngestionPlan`, so preview and submission still use
identical chunks.

The recommended application DTO shape is:

```text
planId
sourceId
sourceVersion
sourceName
entityType
tenantId
visibility
documentCount
chunkCount
totalContentLength
droppedMetadataCount
chunks[]
warnings[]
```

Each application chunk-preview item contains:

```text
sourceDocumentId
chunkId
chunkIndex
chunkCount
entityId
contentPreview
contentLength
contentFingerprint
safeMetadata
warnings[]
```

Rules for every preview implementation:

- content preview is always bounded by configuration;
- preview never exposes full content, local paths, credentials, parser payloads, embeddings, or raw
  provider data;
- preview generation derives from an already validated plan;
- preview contains no mutable reference to `AIIndexDocument`.

Promotion to a shared `DocumentIngestionPreview` and `DocumentChunkPreview` contract requires a
second non-workbench consumer with the same serialization needs. This avoids freezing one demo's UI
projection as a framework API.

### `DocumentIngestionManifest`

Applications persist this framework value or wrap it in an application-owned source-run entity.

```text
schemaVersion
manifestId
planId
sourceId
sourceVersion
sourceName
entityType
tenantId
visibility
createdAt
chunks[]
```

Each `DocumentManifestChunk` contains:

```text
sourceDocumentId
chunkId
chunkIndex
entityId
contentFingerprint
```

Rules:

- manifest contains no source content or content preview;
- manifest contains every final entity id required for exact deletion;
- `manifestId` is deterministic from `planId` and ordered entity ids;
- applications own lifecycle state such as `DRAFT`, `INDEXING`, `ACTIVE`, `SUPERSEDED`, `DELETING`,
  `DELETED`, or `FAILED`; these workflow states do not belong in the immutable framework manifest.

### Existing Queue Submission Evidence

The 0.8 implementation does not add `DocumentIndexingSubmission` or
`DocumentIndexingWorkReceipt`. `DocumentIndexingQueueAdapter` submits each plan chunk through the
existing `IndexingQueueService` and returns existing immutable queue entries or their work ids.

Queue acceptance proves only durable acceptance, not successful vector indexing. Applications must
inspect the existing indexing work-status API before activating a manifest. A document-specific
receipt may be introduced later only if existing queue evidence cannot represent a proven consumer
workflow.

### Warnings

Use a stable code plus bounded safe detail:

```text
METADATA_KEY_DROPPED
METADATA_VALUE_TRUNCATED
METADATA_LIMIT_REACHED
PREVIEW_CONTENT_BOUNDED
PARSER_DOCUMENT_SKIPPED_EMPTY
TRANSFORMER_DOCUMENT_SKIPPED_EMPTY
```

Warnings may describe safe key names and counts. They must never include dropped secret values or
raw content.

## Spring AI Adapter API

Replace direct conversion/queue convenience behavior with this lifecycle:

```java
DocumentIngestionPlan plan(
    DocumentReader reader,
    SpringAiDocumentIndexingOptions options
);

DocumentIngestionPlan plan(
    List<Document> documents,
    SpringAiDocumentIndexingOptions options
);

DocumentIngestionManifest manifest(DocumentIngestionPlan plan);
```

Submission is intentionally separate:

```java
List<IndexingQueueEntry> submit(
    DocumentIngestionPlan plan,
    IndexingStrategy strategy,
    LocalDateTime scheduledFor
);
List<AIIndexDocument> deletionDocuments(
    DocumentIngestionManifest manifest,
    Instant occurredAt
);
```

Implementation decisions:

- `SpringAiDocumentIndexingAdapter` owns Spring AI reading/transformation and plan creation.
- `DocumentIndexingQueueAdapter` owns queue submission and manifest delete-document creation while
  returning existing indexing work evidence.
- API/UI preview projection remains in the application for 0.8;
- submission revalidates current entity configuration before queue writes;
- queue strategy and schedule are supplied at submission time rather than becoming document-plan
  identity;
- partial queue acceptance remains visible through a typed failure containing already accepted
  existing work ids. It must not return an all-success result;
- existing `toIndexDocuments(...)`, direct `enqueue(...)`, and single `toDeleteDocument(...)`
  convenience methods are removed after all repository callers migrate. Their functionality remains
  available through plan, submit, and manifest operations.

## `SpringAiDocumentIndexingOptions`

Retain the builder but make identity and policy explicit:

```text
entityType                 required
sourceId                   required, stable, non-PII
sourceVersion              required, non-negative
sourceName                 optional safe display label
tenantId                   conditionally required by entity/access policy
visibility                 optional bounded policy label
operation                  CREATE or UPDATE
transformers               ordered Spring AI transformers
splitWithTokenTextSplitter defaults true
tokenChunkSize             bounded
maxDocuments               bounded
maxChunks                  bounded
maxContentLength           per chunk
maxTotalContentLength      per plan
maxMetadataEntries         per chunk
maxMetadataValueLength     per value
allowedMetadataKeys        explicit application/parser additions
metadata                   trusted application metadata
correlationId              optional bounded trace id
occurredAt                 optional
```

`DELETE` is not accepted as a plan operation. `DocumentIndexingQueueAdapter` requires a concrete
`SYNC`, `ASYNC`, or `BATCH` strategy at submission; `AUTO` is rejected there.

## Deterministic Identity

### Source And Version

- `sourceId` identifies one logical source and must not contain credentials or user PII.
- `sourceVersion` identifies one immutable source revision.
- applications increment the version only when source content or parsing policy changes.

### Chunk Identity

Use a provider-safe lowercase SHA-256 identity over:

```text
entityType
sourceId
sourceVersion
documentOrdinal
chunkIndex
contentFingerprint
```

Recommended values:

```text
chunkId  = first 40 hexadecimal characters of the identity hash
entityId = "aidoc-" + first 48 hexadecimal characters of the identity hash
```

Rules:

- same source revision and transformed content produces the same ids;
- changed content or source version produces different ids;
- new and old versions can coexist during two-phase reindex;
- ids are independent of random Spring AI `Document` ids;
- `sourceDocumentId` remains attribution metadata but is not the sole identity input;
- all supported vector providers receive the same final `entityId`.

The current `springai-...` id format is replaced. This is an intentional greenfield correction and
requires a minor release. Existing app manifests must delete old ids before or during migration.

## Metadata Contract

### Protected Framework Keys

Use provider-neutral names rather than `_springAi*` names:

```text
_aiDocumentSourceId
_aiDocumentSourceVersion
_aiDocumentSourceName
_aiDocumentId
_aiDocumentChunkId
_aiDocumentChunkIndex
_aiDocumentChunkCount
_aiDocumentContentFingerprint
_aiDocumentTenantId
_aiDocumentVisibility
```

Application and parser metadata cannot override protected keys.

### Allowed Metadata

Default portable keys include:

```text
documentTitle
documentSection
sourceType
locale
createdAt
updatedAt
attributionLabel
```

Additional parser/application keys must be declared through `allowedMetadataKeys`. Default behavior
is allowlist-only, not allow-all followed by best-effort cleanup.

### Blocked Metadata Families

```text
authorization
cookie
credential
password
secret
token
apiKey
url
uri
path
filePath
prompt
completion
embedding
vector
raw
```

Parser-provided URL/path fields are never retained automatically. Applications that need clickable
citations should store a safe source id and resolve its public URL from their source registry after
retrieval. This avoids indexing signed URLs, local paths, query tokens, or credentials.

### Normalization Behavior

- trim and validate key names;
- reject duplicate keys after normalization;
- permit only JSON-safe scalar values in the first release;
- bound key count, key length, and value length;
- truncate permitted overlong display values with a warning;
- drop blocked, undeclared, nested, binary, or unsupported values with warning evidence;
- fail the plan when required protected identity metadata cannot be produced;
- never log dropped values.

## Internal Queue Submission

Use this path when document ETL runs in the same application as AI Fabric indexing.

```text
plan
  -> app persists DRAFT manifest
  -> submit plan plus concrete strategy/schedule through DocumentIndexingQueueAdapter
  -> app records existing queue work ids and marks INDEXING
  -> IndexingWorkQuery confirms every UPSERT COMPLETED
  -> optional retrieval-evidence verification
  -> app marks manifest ACTIVE
```

Queue acceptance is not indexing completion. The workbench and documentation must not label a source
`INDEXED` immediately after enqueueing.

## Demand-Gated Data Sync Submission

This design is retained for later, but it is not implemented or released in 0.8. Implement it when
LoomAI or another concrete application prepares documents in one service and submits them to a
separate target runtime.

`DocumentDataSyncMapper` provides:

```java
DataSyncBatchRequest toUpsertBatch(
    DocumentIngestionPlan plan,
    DataSyncTrace verifiedTrace
);

DataSyncBatchRequest toDeleteBatch(
    DocumentIngestionManifest manifest,
    DataSyncTrace verifiedTrace
);
```

Mapping decisions:

- each plan chunk becomes one `DataSyncOperation`;
- `vectorSpace` is the plan entity type;
- `id` is the final deterministic chunk `entityId`;
- `content` is `AIIndexDocument.semanticSearchText`;
- normalized safe metadata is copied from `AIIndexDocument.vectorMetadata`;
- `entity` is omitted because content is already normalized;
- `DataSyncIdentity` is omitted in the first implementation because `id` is already the final
  deterministic chunk id. Supplying another `chunkId` would cause Data Sync to derive a second id;
- the caller must supply `DataSyncTrace` with verified backend auth context. The mapper never invents
  subject, tenant, deployment, issuer, or scopes;
- the target runtime continues to validate vector space, access, projection, and provider outcome;
- all batch results must succeed before the application activates a manifest.

When justified, this mapper belongs in `ai-fabric-data-sync`, preserving the existing dependency
direction. Until then, callers may construct the existing Data Sync operations explicitly if they
need an exceptional cross-service path.

## Application-Owned Two-Phase Reindex

The current workbench delete-first behavior must be replaced. AI Fabric supplies deterministic
identities, exact manifests, queue work evidence, and delete documents; the workbench or LoomAI owns
the persisted lifecycle states and transition orchestration.

Required state sequence:

```text
old manifest ACTIVE
  -> build new plan with sourceVersion + 1
  -> persist new manifest DRAFT
  -> submit new chunks
  -> new manifest INDEXING
  -> verify every new chunk completed successfully
  -> optionally verify expected source/version in retrieval evidence
  -> new manifest ACTIVE
  -> old manifest SUPERSEDED
  -> submit explicit deletes for old manifest ids
  -> verify every delete completed
  -> old manifest DELETED
```

If planning, submission, indexing, or verification fails:

- keep the old manifest `ACTIVE`;
- mark the new app-owned run `FAILED`;
- retain work/error evidence;
- delete only successfully written new-version chunks during cleanup;
- never delete the old searchable version.

A short overlap where both versions are searchable is acceptable and safer than a content gap.
Applications may filter retrieval to the active source version when strict single-version visibility
is required.

## Delete Lifecycle

Deletion always starts from a persisted manifest:

```text
manifest
  -> exact entity ids
  -> payload-free AIIndexDocument DELETE work
  -> verify each outcome
  -> retrieval smoke confirms the source/version is absent
  -> application marks manifest DELETED
```

A future Data Sync mapper must produce delete operations for the same exact entity ids rather than
introducing a second deletion identity.

Rules:

- no broad delete-by-metadata in the first release;
- internal delete uses canonical payload-free `AIIndexDocument` objects;
- delete fails closed when entity type, source identity, tenant requirement, or chunk ids are missing;
- partial delete remains `DELETING` or `FAILED`, never `DELETED`;
- retries are idempotent because entity ids are deterministic.

## Trust And Security Requirements

### Resource Entry

- file resources must resolve beneath an explicitly trusted root;
- classpath and in-memory resources remain allowed only through explicit policy methods;
- HTTP/HTTPS and other remote resources are rejected by the framework reader factory;
- direct `List<Document>` planning is a trusted server-side API and must not be exposed directly to
  untrusted request payloads;
- upload APIs first persist content into application-owned trusted storage, then create a reader.

### Plan Validation

Fail closed for:

- missing or invalid source identity/version;
- missing tenant when target policy requires it;
- unknown or non-indexable entity/vector space;
- unsupported resource or reader;
- parser or transformer failure;
- empty complete input;
- document, chunk, content, or total-size limit breach;
- protected metadata override;
- non-JSON-safe canonical metadata;
- descriptor/projection validation failure.

### Submission Validation

- revalidate entity configuration and indexability at submission time;
- verify the immutable plan remains internally consistent before submission;
- reject mixed-entity plans;
- never accept tenant identity solely from arbitrary parser metadata;
- redact source content and metadata values from failure logs.

Serialized-plan schema negotiation, signatures, and remote tamper detection are deferred until plans
cross a service or trust boundary. A future Data Sync mapper must always use verified server-side auth
context.

## Configuration

Extend the existing `AIIndexingProperties` namespace; do not introduce
`ai.infrastructure.indexing.*`.

```yaml
ai:
  indexing:
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

Rules:

- configuration supplies upper defaults; per-request options may tighten but not exceed them;
- no global trusted root is assumed. Callers pass a `SpringAiTrustedResourcePolicy`, or an
  application explicitly configures and constructs one;
- document support is conditional on Spring AI classes and `ai.indexing.documents.enabled=true`;
- the existing indexing queue and worker properties remain unchanged;
- invalid or unsafe bounds fail application startup through property validation.

Preview length is an application/API projection concern in 0.8. The workbench configures its own
bounded preview length rather than expanding the framework property surface.

## Reader Roadmap

### Required For AI Fabric 0.8

- Spring AI `TextReader`;
- Spring AI `JsonReader`;
- caller-supplied Spring AI `DocumentTransformer`s;
- Spring AI `TokenTextSplitter`.

### Follow-Up Evaluation

- Markdown reader;
- HTML reader;
- Tika/PDF reader;
- format-specific metadata extraction.

Each optional reader requires:

- an optional dependency or separate reader module;
- conditional bean/factory behavior;
- trust validation before parser construction;
- file/page/content limits;
- malformed, encrypted, oversized, and adversarial fixture tests;
- metadata sanitization tests;
- no production-support claim until a real document smoke passes.

OCR and arbitrary URL fetching remain separate product/integration proposals.

## Observability And Failure Model

### Evidence Fields

Plan, submission, and app-owned lifecycle responses should expose safe identifiers and counts:

```text
planId
manifestId
sourceId
sourceVersion
entityType
tenantId
documentCount
chunkCount
queuedCount
completedCount
failedCount
deletedCount
droppedMetadataCount
warningCodes
failureCode
safeFailureMessage
correlationId
traceId
```

Never put document content, content previews, secret metadata values, embeddings, or provider payloads
in metrics tags or logs.

### Failure Codes

The 0.8 public contract starts with the smallest stable set that callers can act on:

```text
DOCUMENT_RESOURCE_UNTRUSTED
DOCUMENT_PARSE_FAILED
DOCUMENT_LIMIT_EXCEEDED
DOCUMENT_METADATA_REJECTED
DOCUMENT_INDEXING_FAILED
DOCUMENT_DELETE_FAILED
```

Use typed exceptions/results for stable codes. Exception class names and raw parser/provider messages
must not become the public error contract. Safe internal causes may distinguish unsupported readers,
transform failures, empty input, unknown entity types, missing tenants, projection failures, and
partial queue acceptance without freezing each distinction as a public code.

The following finer codes remain candidates for later promotion when operations prove callers need
to react differently:

```text
DOCUMENT_READER_UNSUPPORTED
DOCUMENT_TRANSFORM_FAILED
DOCUMENT_INPUT_EMPTY
DOCUMENT_PLAN_LIMIT_EXCEEDED
DOCUMENT_PLAN_TAMPERED
DOCUMENT_ENTITY_TYPE_UNKNOWN
DOCUMENT_ENTITY_NOT_INDEXABLE
DOCUMENT_TENANT_REQUIRED
DOCUMENT_PROJECTION_REJECTED
DOCUMENT_QUEUE_SUBMISSION_FAILED
DOCUMENT_QUEUE_SUBMISSION_PARTIAL
DOCUMENT_DATASYNC_BATCH_FAILED
DOCUMENT_INDEXING_INCOMPLETE
DOCUMENT_DELETE_INCOMPLETE
```

`DOCUMENT_PLAN_TAMPERED` and `DOCUMENT_DATASYNC_BATCH_FAILED` are specifically deferred until plans
cross a trust boundary and the document-specific Data Sync mapper exists.

## AI Fabric 0.8 Implementation Work Packages

### Work Package 1: Core Models And Policy

Add:

- document model records/classes;
- warning and failure codes;
- `DocumentMetadataNormalizer`;
- `DocumentChunkIdentity`;
- nested `AIIndexingProperties.DocumentProperties` with validation;
- unit tests for immutability, bounds, protected metadata, and deterministic ids.

Modify:

- `AIIndexingAutoConfiguration` to condition document beans on the new property;
- configuration metadata/documentation.

Acceptance:

- model package has no Spring AI imports;
- no queue/vector interaction occurs during planning-model tests;
- repeated identity input produces byte-for-byte equal ids;
- changing source version or content changes ids;
- manifest contains exact entity ids and no full content.

### Work Package 2: Spring AI Planning Adapter

Refactor `SpringAiDocumentIndexingAdapter` to:

- read and transform Spring AI documents;
- enforce document/chunk/total bounds;
- skip empty transformed chunks with warnings;
- use provider-neutral protected metadata;
- create canonical `AIIndexDocument` chunks;
- return plan and manifest;
- remove queue submission responsibility.

Extend `SpringAiDocumentIndexingOptions` with source version, tenant/visibility, total bounds,
allowlisted metadata, and correlation id.

Acceptance:

- text and JSON readers produce deterministic plans;
- custom transformer order is preserved;
- planning makes no queue, embedding, or vector call;
- unknown/non-indexable entity type fails before returning a plan;
- parser metadata cannot override protected metadata.

### Work Package 3: Existing Queue Submission And Delete

Add `DocumentIndexingQueueAdapter` and `DocumentManifestOperations` without introducing a new
submission receipt hierarchy.

Acceptance:

- every plan chunk is submitted as its canonical `AIIndexDocument`;
- existing queue entries or work ids are returned exactly;
- submission revalidates internal plan consistency, entity type, and indexability;
- manifest deletes are payload-free canonical DELETE documents;
- partial acceptance is visible and tested;
- no success status implies worker completion.

### Work Package 4: Workbench Migration

Refactor `document-ingestion-workbench` to:

- use framework plan, manifest, queue, and delete helpers;
- own a bounded preview DTO projected from the validated plan;
- persist both current active and pending manifests;
- track queue work ids and lifecycle status;
- activate only after indexing completion;
- perform new-first, old-delete-second reindex;
- expose a RAG query endpoint returning source/chunk evidence;
- keep text and JSON upload support;
- return typed safe failures.

Remove duplicated app-local chunk planning and manifest conversion once framework equivalents are
used.

Acceptance:

- preview contains no full unbounded content, local paths, credentials, or embeddings;
- a failed replacement leaves old content retrievable;
- a successful replacement activates the new version before deleting old chunks;
- delete waits for exact deletion outcomes;
- unsupported resources fail before plan creation;
- controller never serializes a full plan.

Strict active-version filtering is not required for 0.8. A short overlap during successful
new-first replacement is acceptable and is tested explicitly.

### Work Package 5: Release Proof And Documentation

Add:

- `.github/scripts/smoke-document-ingestion-workbench.sh`;
- lifecycle documentation and request examples;
- release notes and migration notes;
- an optional keyed OpenAI embedding smoke outside the default unit-test path.

Acceptance:

- deterministic smoke proves preview, index, retrieval evidence, replacement, deletion, and negative
  cases;
- packaged JAR smoke passes, not only test-classpath execution;
- no fallback masks parser, embedding, vector, or RAG failures;
- full framework reactor passes.

## Demand-Gated Follow-Up Work Packages

These packages retain the valid ideas from the broader proposal, but none is a 0.8 release blocker.

### Follow-Up A: Shared Preview Contract

Promote the workbench preview DTO into framework `DocumentIngestionPreview` and
`DocumentChunkPreview` types only after a second application needs the same bounded serialization
shape.

Reason for deferral: preview is presentation-specific, and publishing one demo's shape now would
create avoidable compatibility obligations.

### Follow-Up B: Data Sync Mapper

Add `DocumentDataSyncMapper` under `ai-fabric-data-sync` when LoomAI has a deployed cross-service
document ingestion flow.

Acceptance when triggered:

- upsert and delete batches preserve exact final entity ids;
- mapper requires caller-supplied `DataSyncTrace`;
- no identity/auth context is copied from parser metadata;
- missing verified auth still fails in the target `DataSyncService`;
- batch bounds are checked against Data Sync configuration before transport;
- no module dependency cycle is introduced.

Reason for deferral: there is currently no concrete remote-plan consumer, so the DTO mapping and
remote failure semantics would be designed speculatively.

### Follow-Up C: Remote Plan Protocol

Add serialized plan schemas, compatibility rules, signatures or tamper checks, and dedicated remote
submission receipts only when plans cross a process or trust boundary.

Reason for deferral: immutable in-process plans are already revalidated before submission and do not
justify a second transport protocol.

### Follow-Up D: Optional Reader Expansion

Evaluate Markdown, HTML, PDF/Tika, OCR, and multimodal extraction independently after the 0.8 core is
release-ready. Each reader requires format-specific resource bounds and adversarial fixtures.

Reason for deferral: format parsing is commodity infrastructure with a larger dependency and
security surface than its contribution to the core AI Fabric lifecycle proof.

### Follow-Up E: Quality And Provider Optimization

Evaluate RAG quality scorecards, automatic evaluator gates, strict active-version filtering, and
provider-native bulk operations after real document corpora and expected-answer datasets exist.

Reason for deferral: relevance thresholds and native optimizations cannot be designed honestly from
one small workbench dataset.

## AI Fabric 0.8 Required Test Matrix

### `ai-fabric-indexing`

- plan determinism;
- version-sensitive chunk ids;
- immutable plan consistency validation;
- framework metadata protection;
- application metadata allowlist;
- blocked metadata values never appear in logs or warnings;
- text reader trust checks;
- JSON reader trust checks;
- remote and out-of-root resource rejection;
- parser and transformer failures;
- empty document handling;
- max documents, chunks, chunk length, and total length;
- unknown/disabled entity type;
- existing queue work-id and partial submission behavior;
- exact manifest deletes;
- auto-configuration enabled, disabled, and Spring AI classpath conditions.

### Deferred `ai-fabric-data-sync` Mapper Tests

These tests become required only when Follow-Up B is triggered:

- plan-to-upsert mapping;
- manifest-to-delete mapping;
- exact id parity with internal mode;
- trace/auth context preservation;
- no parser metadata identity spoofing;
- target access denial;
- mixed batch success/failure evidence;
- batch-size rejection.

### `document-ingestion-workbench`

- upload and preview without queue writes;
- preview redaction and bounds;
- initial index state transition;
- queue failure remains visible;
- replacement indexes new chunks before old deletes;
- failed replacement preserves old active manifest;
- successful replacement retires old manifest;
- exact delete lifecycle;
- tenant isolation;
- RAG evidence includes expected source/version/chunk identity;
- deleted source is no longer retrievable;
- unsupported type and oversized input fail closed.

## Build And Verification Commands

Focused framework tests:

```bash
mvn -B -V --no-transfer-progress \
  -f ai-infrastructure-module/pom.xml \
  -pl ai-fabric-indexing,ai-fabric-rag -am test
```

Real-app tests:

```bash
mvn -B -V --no-transfer-progress \
  -f examples/real-apps/pom.xml \
  -pl document-ingestion-workbench -am test
```

Packaged deterministic smoke:

```bash
.github/scripts/smoke-document-ingestion-workbench.sh
```

Full release build, with the local ONNX test model paths when the integration suite requires them:

```bash
mvn -B -V --no-transfer-progress \
  -f ai-infrastructure-module/pom.xml \
  -DONNX_MODEL_PATH="$PWD/ai-infrastructure-module/models/embeddings/all-MiniLM-L6-v2.onnx" \
  -DONNX_TOKENIZER_PATH="$PWD/ai-infrastructure-module/models/embeddings/tokenizer.json" \
  clean verify
```

The keyed provider smoke is additional evidence, not a substitute for deterministic tests.

## End-To-End Smoke Scenario

The packaged release smoke must execute these observable steps:

1. Start the packaged workbench with H2, deterministic embeddings, and a local vector provider.
2. Create a trusted text source at version 1.
3. Preview it and assert no queue/vector write occurred.
4. Submit it and wait for every chunk work item to complete.
5. Ask a question and assert evidence contains source id, version 1, and a manifest chunk id.
6. Replace the source with version 2.
7. Reject an invalid replacement and prove version 1 remains retrievable.
8. Submit a valid version 2 and activate it successfully.
9. Delete version-1 chunks and prove they disappear.
10. Delete the source through its active manifest and prove no source evidence remains.
11. Attempt unsupported, oversized, and cross-tenant requests and assert fail-closed behavior.
12. Repeat the lifecycle for a JSON source and prove preview, activation, evidence, and deletion.

Focused framework and workbench tests provide the non-HTTP failure injection that cannot be driven
honestly through the public demo API. They reject remote and out-of-root resources, reject protected
parser-metadata overrides, expose partial queue acceptance, inject failed candidate work, prove the
old active version survives, and verify exact candidate cleanup submission.

## Release And Migration

This work targets a minor release because it adds public contracts and intentionally corrects the
current Spring AI document adapter shape.

Release notes must state:

- `AIIndexDocument` remains the sole canonical indexing payload;
- direct adapter queue methods are replaced by planning plus the existing-queue adapter;
- source version becomes required;
- chunk ids change from `springai-*` to versioned `aidoc-*` ids;
- `_springAi*` metadata becomes provider-neutral `_aiDocument*` metadata;
- manifests generated by the old workbench must be explicitly deleted or reindexed;
- no document-specific Data Sync mapper is included in 0.8;
- Spring AI remains optional and is used for document ETL, not vector lifecycle.

There is no compatibility shim. Repository examples, tests, guides, and LoomAI adoption notes must
migrate in the same release.

## Resolved Design Decisions

1. **Full content in plan:** yes, server-side only. The application owns the bounded API-safe preview
   projection in 0.8.
2. **Version in chunk identity:** yes. This is required for non-destructive two-phase reindex.
3. **Internal delete contract:** use canonical payload-free `AIIndexDocument` DELETE work, not a new
   `IndexingDeleteRequest` type.
4. **Metadata default:** allowlist-only, with explicit application additions and protected framework
   keys.
5. **Data Sync timing:** deferred until LoomAI proves a cross-service document flow.
6. **Future Data Sync location and id:** implement in `ai-fabric-data-sync`; send the final
   deterministic `entityId` directly and do not derive a second chunk id.
7. **Manifest persistence:** application or Platform owned; AI Fabric supplies immutable contracts and
   operations.
8. **Format readers:** text/JSON first; optional readers follow independently after lifecycle proof.
9. **Remote URL ingestion:** excluded and rejected by default.
10. **Spring AI VectorStore:** not used; AI Fabric retains its mature provider and lifecycle contract.

## Definition Of Done

The capability is complete only when:

- AI Fabric 0.8 Work Packages 1 through 5 are implemented;
- all required tests and packaged smoke scenarios pass;
- the workbench contains no duplicate document planning logic;
- reindex never deletes the active version before replacement success;
- tenant and auth context cannot be supplied through untrusted parser metadata;
- preview and observability surfaces contain no full content or sensitive values;
- RAG evidence identifies source, version, and chunk;
- delete verification proves removed evidence is no longer retrievable;
- framework, real-app, migration, and release documentation agree;
- the full Maven reactor passes with tests enabled.

## Deferred Capability Register

Every deferred idea remains recorded here with the evidence required to start it.

| Deferred capability | Owner | Trigger to reconsider | Reason for waiting |
| --- | --- | --- | --- |
| Shared preview DTOs | AI Fabric | A second real app needs the same wire shape | Avoid freezing workbench presentation choices. |
| Document-specific Data Sync mapper | AI Fabric Data Sync | LoomAI deploys cross-service document preparation | No current remote consumer or validated transport semantics. |
| Remote plan schema, signing, and tamper handling | AI Fabric | Plans cross a process or trust boundary | In-process immutable plans do not need a protocol. |
| Dedicated document submission receipts | AI Fabric | Existing queue work evidence cannot serve a real workflow | Avoid duplicating indexing status contracts. |
| Generic reindex state machine | LoomAI Platform/application | Two products share identical durable transition semantics | Workflow persistence and retry policy are product-specific. |
| Strict active-version retrieval filter | AI Fabric RAG | A use case cannot tolerate brief version overlap | Safe new-first replacement works without adding query coupling. |
| Markdown and HTML readers | Optional reader integration | A product commits representative fixtures | Each parser needs format-specific trust and bounds tests. |
| PDF/Tika and parser hardening | Optional reader integration | Searchable PDFs become a committed product requirement | Dependency, memory, malformed-file, and encrypted-file risks are substantial. |
| OCR and multimodal extraction | LoomAI integration | Image/scanned-document demand and provider are selected | Expensive and provider-specific; unrelated to proving lifecycle governance. |
| Managed source connectors | LoomAI Platform | A named connector has an owner and customer workflow | Credentials, polling, and source authorization are Platform concerns. |
| Signed object-storage ingestion | LoomAI Platform | Object storage is selected for managed uploads | Signing, expiry, storage tenancy, and retention belong with source storage. |
| Malware and DLP integration | LoomAI Platform | External file upload becomes a supported product surface | Requires operational security products and policy, not a framework parser. |
| Source retention scheduling | LoomAI Platform | Persisted sources have a contractual retention requirement | The framework does not own source bytes or lifecycle jobs. |
| Document-quality scorecards and Spring AI evaluator gates | AI Fabric examples/evaluation | Stable corpus, questions, and expected answers exist | Scores without representative datasets would be misleading. |
| Provider-native bulk optimizations | Vector providers | Neutral implementation has parity evidence and a measured bottleneck | Optimize only after correctness and portability. |
| Approval, billing, quotas, and operator UI | LoomAI Platform | Managed document ingestion becomes a product | These are commercial and operational workflows, not indexing primitives. |

Deferred capabilities must extend the same plan, manifest, `AIIndexDocument`, queue, and retrieval
contracts. They must not create a parallel document indexing path.
