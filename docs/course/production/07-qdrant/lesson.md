---
id: prod-07
slug: qdrant
title: Move To A Managed Vector Provider
track: production
order: 7
durationMinutes: 90
availability: preview
courseVersion: 0.4.0-course.2-beta
frameworkVersion: 0.4.0
frameworkTag: ai-fabric-framework-v0.4.0
courseSourceTag: ai-fabric-course-v0.4.0.2
starterRef: course-0.4.0-p06-rag-quality
solutionRef: course-0.4.0-p07-qdrant
requiresOpenAi: false
requiresDocker: true
optionalProviderExercises:
  - qdrant-cloud
sourcePaths:
  - docs/course/production/07-qdrant/notebooklm/AI_FABRIC_MANAGED_VECTOR_PROVIDER_QDRANT_NOTEBOOKLM_SCRIPT.md
  - ai-infrastructure-module/ai-fabric-core/src/main/java/ai/fabric/rag/VectorDatabaseService.java
  - ai-infrastructure-module/victor-databases/ai-fabric-vector-qdrant/src/main/java/ai/fabric/vector/qdrant/QdrantVectorAutoConfiguration.java
  - ai-infrastructure-module/victor-databases/ai-fabric-vector-qdrant/src/main/java/ai/fabric/vector/qdrant/QdrantVectorDatabaseService.java
theoryVideoIds:
  - managed-vector-provider-qdrant
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
  targetDurationMinutes: 10
  title: Replacing A Vector Provider Without Rewriting The App
  publicUrl: https://www.youtube.com/watch?v=TCgEbDsUzic
  transcript: notebooklm/AI_FABRIC_MANAGED_VECTOR_PROVIDER_QDRANT_NOTEBOOKLM_SCRIPT.md
  sourceManifest: notebooklm/source-manifest.yml
---

# Move To A Managed Vector Provider

You will replace Lucene with Docker Qdrant without changing the application retrieval contract.
This is a provider substitution exercise, not a rewrite of RAG, tenant policy, migration, or Data
Sync.

## Outcome

You will:

- add the published `ai-fabric-vector-qdrant` module beside Lucene;
- select Qdrant through a dedicated Spring profile;
- keep ONNX embeddings and 384 dimensions;
- create a scoped collection and required payload index;
- prove metadata filtering and post-hit tenant verification;
- rerun the PROD-06 golden scorecard unchanged;
- prove create, stable update, delete, count, and readiness behavior;
- make an unreachable Qdrant fail visibly with no Lucene fallback;
- optionally point the same profile at Qdrant Cloud through runtime secrets.

## Start Here

```bash
git clone https://github.com/Loom-AI-Labs/ai-fabric-course-support-assistant.git
cd ai-fabric-course-support-assistant
git fetch --tags
git show-ref --verify --quiet refs/tags/course-0.4.0-p06-rag-quality \
  || { echo "The required 0.4 starter checkpoint could not be resolved."; exit 1; }
git switch --detach course-0.4.0-p06-rag-quality
./mvnw --batch-mode --no-transfer-progress clean verify
./scripts/download-onnx-model.sh
```

Docker must be running. No API key is required for local Qdrant.

## What Changes And What Does Not

```text
unchanged:
  application source database
  -> AI Fabric projection
  -> ONNX embedding (384 dimensions)
  -> tenant/status/visibility filters
  -> RAG quality contract

changed:
  Lucene VectorDatabaseService
  -> Qdrant VectorDatabaseService
```

Application controllers and services continue to depend on `VectorDatabaseService`,
`AICoreService`, and `DataSyncService`. No Qdrant SDK appears in application business code.

## Step 1: Add The Provider Module

Add the Maven Central artifact under the existing AI Fabric BOM:

```xml
<dependency>
  <groupId>io.github.loom-ai-labs</groupId>
  <artifactId>ai-fabric-vector-qdrant</artifactId>
</dependency>
```

Keep `ai-fabric-vector-lucene`; the `local` profile remains the portable, keyless Lucene gate. The
active `ai.vector-db.type` selects one provider.

## Step 2: Add A Qdrant Profile

`application-qdrant.yml` selects:

```yaml
ai:
  providers:
    embedding-provider: onnx
    enable-fallback: false
    qdrant:
      enabled: true
      host: ${AI_PROVIDERS_QDRANT_HOST:127.0.0.1}
      port: ${AI_PROVIDERS_QDRANT_PORT:6333}
      grpc-port: ${AI_PROVIDERS_QDRANT_GRPC_PORT:6334}
      api-key: ${AI_PROVIDERS_QDRANT_API_KEY:}
      prefer-grpc: ${AI_PROVIDERS_QDRANT_PREFER_GRPC:false}
      collection-prefix: ${AI_PROVIDERS_QDRANT_COLLECTION_PREFIX:course_prod07__}
  vector-db:
    type: qdrant
```

The prefix isolates course collections from unrelated applications using the same local instance.
It is not a substitute for tenant metadata: both course tenants intentionally share one entity-type
collection and are separated by exact metadata filters plus application post-hit checks.

## Step 3: Start Qdrant

Manual durable environment:

```bash
docker compose -f compose.qdrant.yml up -d
curl --fail http://localhost:6333/readyz
./mvnw spring-boot:run -Dspring-boot.run.profiles=qdrant
```

The compose file pins `qdrant/qdrant:v1.16.1` and uses a named volume. Do not use an unpinned
`latest` image in release proof.

## Step 4: Index And Inspect The Collection

```bash
curl -s -X POST http://localhost:8080/api/demo/seed
curl -s -X POST http://localhost:8080/api/demo/index | jq
curl -s http://localhost:6333/collections/course_prod07__knowledge-article | jq
```

Verify:

- collection vector size is `384`;
- distance is `Cosine`;
- `knowledgeSourceHandleRef` has a keyword payload schema;
- the application reports nine indexed knowledge vectors.

Dimensions are an application/provider contract. Switching to an embedding model with different
dimensions requires a separately named collection or controlled reindex; do not write mixed vector
sizes into an existing collection.

## Step 5: Inspect Typed Provider Readiness

`/api/demo/readiness` now projects safe capabilities from `VectorDatabaseService`:

```json
{
  "vectorProvider": {
    "provider": "qdrant",
    "nativeClient": "qdrant-rest-api",
    "transport": "rest",
    "scopePrefix": "course_prod07__",
    "searchMetadataFiltering": true,
    "scanMetadataFiltering": true,
    "durableStorage": true,
    "productionProfileSafe": true
  }
}
```

The API does not expose the Qdrant API key. Provider health, source counts, vector counts, migration
queue state, and generation-provider state are different readiness concerns; PROD-08 separates them
further.

## Step 6: Reuse The Quality Contract

Run both existing suites without changing expected IDs:

```bash
curl -s http://localhost:8080/api/quality/rag/golden \
  -H 'Authorization: Bearer course-alex-local-token' | jq
curl -s http://localhost:8080/api/quality/rag/golden \
  -H 'Authorization: Bearer course-riley-local-token' | jq
```

Tenant Blue retrieves `article-vpn-blue`; Tenant Red retrieves `article-vpn-red`; restricted and
cross-tenant IDs remain absent. A provider migration is not complete merely because nine points
exist.

## Step 7: Prove Lifecycle Parity

Create, update, and delete `article-qdrant-sync` through the same trusted application endpoints used
in PROD-05. The create and update responses must return the same vector ID. After delete, source and
Qdrant counts return to nine and search cannot return the deleted evidence.

Qdrant uses provider-durable storage, but vector evidence is still derived. Application source rows
remain authoritative and reconciliation remains necessary after ambiguous network failures.

## Step 8: Prove Visible Failure

The Docker smoke starts a second packaged app configured to an unused Qdrant port. Source seeding
succeeds because it is application-owned. Indexing returns HTTP `503` with
`AI evidence operation failed`.

It does not:

- switch to Lucene;
- return a fake success count;
- delete source rows;
- expose endpoint internals in the public error.

## Required Verification

```bash
./mvnw --batch-mode --no-transfer-progress clean verify
COURSE_SMOKE_USE_EXISTING_JAR=true ./scripts/smoke-packaged.sh
COURSE_SMOKE_USE_EXISTING_JAR=true ./scripts/smoke-qdrant.sh
jq . target/course-release-evidence/qdrant-smoke-summary.json
```

Published checkpoint: `course-0.4.0-p07-qdrant`. Both the packaged Lucene gate and the Docker
Qdrant lifecycle/outage gate passed before publication.

## Optional Qdrant Cloud

Store these only in your shell, IDE secret field, CI secrets, or deployment secret store:

```bash
export AI_PROVIDERS_QDRANT_HOST='https://<cluster-host>'
export AI_PROVIDERS_QDRANT_GRPC_PORT='6334'
export AI_PROVIDERS_QDRANT_API_KEY='<secret>'
export AI_PROVIDERS_QDRANT_PREFER_GRPC='true'
export AI_PROVIDERS_QDRANT_COLLECTION_PREFIX='course_cloud__'
./mvnw spring-boot:run -Dspring-boot.run.profiles=qdrant
```

Confirm the endpoint and TLS/port values from your Qdrant deployment. Never commit the key or put it
in an HTTP request file. Cloud proof is `NOT RUN` until the same dimensions, filtering, lifecycle,
readiness, and failure assertions execute against that deployment. OpenAI is unrelated and not
required.

## Intentional Failures

1. Set `AI_PROVIDERS_QDRANT_PORT` to an unused port. Indexing returns `503`; no fallback occurs.
2. Change ONNX dimensions without recreating/reindexing the collection. The provider rejects the
   mismatch; do not coerce vectors.
3. Remove tenant filters from a test request. The independent post-hit boundary must reject any
   cross-tenant result.
4. Stop Qdrant after source update. Retain source truth and reconcile after provider recovery.

## Done When

- the normal 64-test build passes;
- the packaged Lucene gate still passes;
- Docker Qdrant reports 384 dimensions and the required payload schema;
- provider readiness identifies Qdrant REST and the collection prefix without secrets;
- both tenant golden suites pass unchanged;
- create/update use one stable vector ID and delete restores the count;
- unreachable Qdrant returns 503 with no Lucene or canned fallback;
- Qdrant Cloud remains explicitly optional and honestly labelled.

## Reset And Cleanup

```bash
docker compose -f compose.qdrant.yml down -v
./scripts/reset-course.sh
git switch --detach course-0.4.0-p06-rag-quality
```

The automated smoke removes its temporary container. The manual compose command above removes the
named course volume only when you include `-v`.

## Troubleshooting

**The app starts but indexing fails:** Qdrant is connected lazily. Check `/readyz`, host/port,
transport, API key, and the operation error; process startup alone is not provider readiness.

**Collection size is not 384:** the active embedding profile changed or the collection is stale.
Use a new prefix or perform a controlled clear and reindex.

**Filtered search uses fallback:** inspect `searchFilterMode`, verified payload indexes, and Qdrant
payload schema. Never remove the application post-hit check.

**Cloud gRPC cannot connect:** use an `https://` host to enable TLS in the current adapter, verify the
gRPC port, and keep `prefer-grpc=true`; use the REST profile when your deployment exposes REST only.

## Next Lesson

PROD-08 turns all accumulated tests into an operations and release gate with restart, persistence,
cleanup, build identity, and separately retained optional-provider evidence.
