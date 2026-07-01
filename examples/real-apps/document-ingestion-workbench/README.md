# Document Ingestion Workbench

## Scenario

This app demonstrates trusted knowledge-base ingestion for AI Fabric.

It accepts small text or JSON documents, stores them under an application-controlled trusted root,
previews chunks produced through Spring AI document reader integration, queues AI Fabric indexing
work, and records chunk manifests so reindex/delete operations remove stale vectors.

## AI Fabric Capabilities Proved

- Spring AI document readers can feed AI Fabric indexing without bypassing lifecycle policy.
- Trusted-resource policy guards what readers can access.
- Chunk preview is available before enqueueing indexing work.
- Source re-upload replaces old chunk manifests and queues delete requests for stale chunks.
- Source deletion queues delete requests for every indexed chunk.
- Metadata is normalized and sanitized before it becomes indexing payload evidence.
- Unsupported file or metadata shapes fail closed.
- Smoke mode runs locally with deterministic providers.

## Framework Surfaces

- `ai-fabric-indexing`
- `SpringAiDocumentReaderFactory`
- `SpringAiDocumentIndexingAdapter`
- `SpringAiTrustedResourcePolicy`
- `IndexingQueueService`
- Lucene or memory vector provider depending on profile

## Runtime Posture

Default runtime is local:

- H2 database
- trusted local document root
- local deterministic providers in smoke profile
- no external model required

OpenAI embeddings can be enabled explicitly when needed.

## Run

From the repository root:

```bash
mvn -B -V --no-transfer-progress -f examples/real-apps/pom.xml -pl document-ingestion-workbench -am package
java -jar examples/real-apps/document-ingestion-workbench/target/document-ingestion-workbench-1.0.0-SNAPSHOT.jar \
  --spring.profiles.active=smoke
```

Use OpenAI embeddings with:

```bash
export OPENAI_API_KEY="..."
java -jar examples/real-apps/document-ingestion-workbench/target/document-ingestion-workbench-1.0.0-SNAPSHOT.jar \
  --spring.profiles.active=openai
```

## Validate

Focused tests:

```bash
mvn -B -V --no-transfer-progress -f examples/real-apps/pom.xml -pl document-ingestion-workbench -am test
```

Use `requests/demo.http` to run the scenario.

## Demo Flow

1. Create a document source.
2. Preview chunks before indexing.
3. Queue indexing work and persist chunk manifests.
4. Replace source content.
5. Reindex and verify stale chunks are deleted first.
6. Delete the source and verify delete requests are queued.
7. Try unsupported input and confirm fail-closed behavior.

## Configuration

- `document-workbench.trusted-root`: directory where uploaded source files are written.
- `document-workbench.entity-type`: AI Fabric entity type used for generated chunk payloads.
- `ai.vector-db.type`: defaults to `lucene`; smoke profile switches to `memory`.

## What This App Does Not Cover

- Full PDF/OCR production parsing.
- Live external object storage.
- Retrieval-connector `/retrieval/search` boundary. That should be covered by a separate
  `retrieval-connector-boundary-lab`.
