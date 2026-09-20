# Document Ingestion Workbench

## Scenario

This real app demonstrates governed document ingestion for an AI Fabric knowledge base. It accepts
small text or JSON uploads, stores them beneath an application-controlled trusted root, prepares
deterministic chunks through Spring AI document ETL, and submits canonical `AIIndexDocument` work
through AI Fabric's durable indexing queue.

The app deliberately owns source files, manifest persistence, and lifecycle orchestration. The
framework owns trusted preparation, protected metadata, deterministic identities, canonical queue
payloads, and exact delete work.

## AI Fabric Capabilities Proved

- Spring AI `DocumentReader`, transformers, and token splitting feed AI Fabric without writing
  directly to a Spring AI `VectorStore`.
- Text and JSON resources are read only after an AI Fabric trusted-resource check.
- Preview is bounded and side-effect free: it creates no queue or vector work.
- Parser metadata is allowlisted; tenant and lifecycle metadata cannot be overridden by document
  content or request metadata.
- Source, version, chunk, entity, plan, and manifest identities are deterministic.
- Queue acceptance returns real AI Fabric work IDs and does not pretend indexing has completed.
- A version becomes active only after every indexing work item reaches a successful terminal state.
- Replacement is new-first: the previous active version remains available until the candidate is
  indexed, then its exact manifest entity IDs are deleted.
- A failed candidate remains visible and does not retire the previous active version.
- Retrieval evidence identifies the source, version, chunk, entity ID, score, and safe metadata.
- Source deletion is complete only after every exact vector delete succeeds.
- Unsupported and oversized inputs fail closed with bounded error responses.

## Framework Surfaces

- `ai-fabric-indexing`
- `SpringAiDocumentReaderFactory`
- `SpringAiTrustedResourcePolicy`
- `SpringAiDocumentIndexingAdapter`
- `DocumentIngestionPlan`
- `DocumentIngestionManifest`
- `DocumentIndexingQueueAdapter`
- `DocumentManifestOperations`
- `IndexingWorkQuery`
- `AICoreService.performSearch(...)`

`AIIndexDocument` remains the only indexing payload. The document bridge does not introduce a
second vector lifecycle or a document-specific receipt hierarchy.

## Backend Architecture

```text
multipart upload
  -> app-owned trusted source file + DocumentSource
  -> Spring AI text/JSON DocumentReader
  -> transformer / TokenTextSplitter
  -> AI Fabric metadata policy + deterministic identity
  -> server-side DocumentIngestionPlan
       |-> bounded app preview
       |-> content-free manifest persisted by the app
       +-> DocumentIndexingQueueAdapter
             -> existing indexing queue/workers
             -> configured embedding provider
             -> configured AI Fabric vector provider

GET /api/documents/query
  -> AI Fabric vector search
  -> tenant and active-manifest filter
  -> source/version/chunk evidence
```

Backend dependencies:

- Spring Boot Web, Data JPA, Validation, Actuator, and H2.
- Spring AI commons for document ETL.
- AI Fabric starter, indexing, and Lucene vector modules.
- `smoke-support` for deterministic local providers and deployment metadata.

The app is config-driven and uses no Java AI annotations. `ai-entity-config.yml` declares the `kb`
entity type, its searchable projection, required tenant metadata, and indexability.

## Lifecycle

1. Create a source. The source is `PENDING`; no vectors exist.
2. Preview the source. The response includes bounded text and safe metadata, never the full plan or
   local storage path.
3. Submit indexing. The source becomes `INDEXING` and returns durable work IDs.
4. Poll the source endpoint. Successful worker outcomes promote the manifest to `ACTIVE` and the
   source to `INDEXED`.
5. Replace content. The source advances to a pending version while `activeVersion` still points to
   the old evidence.
6. Submit the replacement. The source reports `REPLACING`. Once all candidate work succeeds, the
   new manifest becomes `ACTIVE`; only then are exact old IDs queued for deletion.
7. Delete the source. It reports `DELETING` until every exact delete is terminal, then `DELETED`.

Calling the status endpoint performs app-owned reconciliation against `IndexingWorkQuery`. Queue
acceptance alone never produces `INDEXED` or `DELETED`.

## API

| Method | Path | Purpose |
| --- | --- | --- |
| `POST` | `/api/documents/sources` | Store a trusted text/JSON source. |
| `PUT` | `/api/documents/sources/{sourceId}/content` | Prepare the next source version. |
| `GET` | `/api/documents/sources/{sourceId}/preview` | Return a bounded preview with no indexing side effects. |
| `POST` | `/api/documents/sources/{sourceId}/index` | Submit canonical chunk work to the existing queue. |
| `GET` | `/api/documents/sources/{sourceId}` | Reconcile and return source, manifest, and work status. |
| `GET` | `/api/documents/query?query=...&tenantId=...` | Return active tenant-scoped retrieval evidence. |
| `DELETE` | `/api/documents/sources/{sourceId}` | Queue exact deletes for the active manifest. |

The query endpoint intentionally returns retrieval evidence rather than an invented answer. An
application may pass that evidence into its normal AI Fabric RAG or specialist flow.

## Run

From the repository root, install the current framework contracts and package the real app:

```bash
mvn -B -V --no-transfer-progress \
  -f ai-infrastructure-module/pom.xml \
  -pl ai-fabric-indexing -am clean install

mvn -B -V --no-transfer-progress \
  -f examples/real-apps/pom.xml \
  -pl document-ingestion-workbench -am clean package

java -jar \
  examples/real-apps/document-ingestion-workbench/target/document-ingestion-workbench-1.0.0-SNAPSHOT.jar \
  --spring.profiles.active=smoke
```

Smoke mode uses deterministic local generation/embedding support and the memory vector provider. It
requires no API key.

Use OpenAI embeddings explicitly with:

```bash
export OPENAI_API_KEY="..."
export AI_EMBEDDING_PROVIDER=openai
export AI_VECTOR_DB_TYPE=lucene

java -jar \
  examples/real-apps/document-ingestion-workbench/target/document-ingestion-workbench-1.0.0-SNAPSHOT.jar \
  --spring.profiles.active=openai
```

## Verify

Focused tests:

```bash
mvn -B -V --no-transfer-progress \
  -f examples/real-apps/pom.xml \
  -pl document-ingestion-workbench -am clean test
```

Packaged deterministic lifecycle smoke:

```bash
mvn -B -V --no-transfer-progress \
  -f examples/real-apps/pom.xml \
  -pl document-ingestion-workbench -am clean package

.github/scripts/smoke-document-ingestion-workbench.sh
```

The smoke proves text and JSON preview, initial activation, tenant-scoped evidence, rejected
replacement safety, new-first successful replacement, exact retirement, source deletion, and
fail-closed inputs against the executable JAR. `requests/demo.http` provides the same flow for
interactive use.

## Configuration

- `document-workbench.trusted-root`: app-owned root for uploaded source files.
- `document-workbench.entity-type`: AI Fabric entity type for prepared chunks.
- `document-workbench.preview.max-characters`: maximum content returned for one preview chunk.
- `document-workbench.preview.max-chunks`: maximum chunks returned by preview.
- `ai.indexing.documents.*`: framework preparation and metadata safety bounds.
- `ai.vector-db.type`: vector provider; smoke uses `memory`, default runtime uses `lucene`.

## Deliberate Boundaries

This app does not implement PDF/OCR, remote URL crawling, object storage, source schedules,
approvals, quotas, or an operator UI. Those are optional reader or application/Platform concerns,
not part of AI Fabric's reduced document-indexing core.
