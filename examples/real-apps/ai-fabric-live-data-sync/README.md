# AI Fabric Live Data Sync

This real app proves that ordinary Spring Boot/JPA entity changes can remain synchronized with the
vector evidence used by retrieval and an LLM. It demonstrates both supported application shapes:

- transparent `@AIProcess` lifecycle processing when a business method does not need a receipt;
- explicit `AIEntityIndexingGateway` submission when an API promises an opaque durable work ID and
  public `IndexingWorkStatus` reconciliation.

No action capability is enabled. The scope is deliberately limited to:

- annotated field extraction;
- create, update, and delete synchronization;
- metadata-filtered retrieval;
- evidence-grounded generation;
- database/vector consistency proof; and
- durable completion, supersession, retry recovery, and dead-letter visibility.

## What The Demo Proves

The demo creates an isolated browser workspace with two records for each of three entity types:

| Entity type | Example evidence |
| --- | --- |
| `sync-product` | NovaBook Air battery and hardware details |
| `sync-policy` | Opened-electronics return window |
| `sync-guide` | Amber SyncLight recovery steps |

The public UI can create, edit, or delete rows. After each normal service call, it independently reads the
database state and vector state to prove:

- updated searchable text replaces the previous vector content;
- `@AIContext` metadata contains the workspace and monotonic source `version`;
- deleted rows no longer have vectors;
- RAG and the LLM see only vectors in the current browser workspace;
- stale work finishes as `SUPERSEDED` without restoring old content; and
- retry and dead-letter transitions retain the original durable work ID.

The live chat uses the independently published
[`@loom-ai-labs/ai-fabric-chat-ui`](https://github.com/Loom-AI-Labs/ai-fabric-chat-ui)
package. The backend returns its typed result contract and does not substitute a deterministic
answer when the live provider fails.

## Annotation Contract

| Annotation | Demo use |
| --- | --- |
| `@EnableAIInfrastructure` | Activates AI Fabric in `LiveDataSyncApplication`. |
| `@AICapable` | Declares each stable entity type, its default and per-operation indexing strategy, and its migration repository. |
| `@AIIdentity` | Marks the stable entity identity used for vector upsert and idempotent delete. |
| `@AISearchable` | Selects priority-ordered, preprocessed text embedded into each vector. |
| `@AIContext` | Adds identifiers, workspace scope, status, dates, prices, and the reserved `version` source-revision metadata without embedding those fields. |
| `@AIProcess` | Observes create, update, and delete service results and routes them through AI Fabric indexing. |

Runtime `ai-entity-config.yml` policy enables indexing and sets the projection budget; configuration
can tighten the destinations declared by annotations but cannot widen them. These are the complete
entity lifecycle annotations used by the current annotation-driven sync contract.
`@AISmartValidation` is a separate validation feature and action annotations are intentionally
outside this demo.

This demo uses the default target convention, so each service method returns the created, updated,
or deleted entity. In particular, delete methods return the deleted snapshot so the aspect retains
the identity needed to remove its vector. Applications with wrapper or void results can instead
declare an `AIProcessTargetResolver`.

## Data And Request Flow

```text
React demo / reusable Chat UI
        |
        | seed/reset lifecycle          | receipt-required edit
        v
Spring MVC controller
        |
        +-> annotated domain method -> AIProcessAspect --+
        |                                             |
        +-> tracked domain method -> AIEntityIndexingGateway
                                                      |
                                                      v
                                  durable indexing work + H2 source row
        |
        | @AISearchable content + @AIContext metadata
        v
Lucene vector store
        |
        | exact workspaceId metadata filter
        v
AI Fabric retrieval -> Spring AI OpenAI generation
```

The controller delegates to application services only. It never sees queue entities or stored
payloads. `DemoMutationService` uses the public gateway because its response contract includes a
receipt; `DemoStateService` reads H2 and Lucene separately only to display lifecycle proof.

## API

Every endpoint except workspace creation and the manifest requires:

```http
X-Demo-Workspace-ID: sync-demo-...
```

| Method | Path | Purpose |
| --- | --- | --- |
| `POST` | `/api/live-sync/workspaces` | Create and seed an isolated six-hour workspace. |
| `POST` | `/api/live-sync/reset` | Delete vectors/rows through annotated methods and recreate the seed set. |
| `GET` | `/api/live-sync/state` | Return independent database/vector state and revision proof. |
| `POST` | `/api/live-sync/entities/{products|policies|guides}` | Create a source entity and return its durable indexing receipt. |
| `PUT` | `/api/live-sync/entities/{products|policies|guides}/{recordKey}` | Update a normal JPA entity and synchronize its vector. |
| `DELETE` | `/api/live-sync/entities/{products|policies|guides}/{recordKey}` | Delete the source entity and its vector. |
| `GET` | `/api/live-sync/indexing-work/{workId}` | Read the safe workspace-bound `IndexingWorkStatus` projection. |
| `POST` | `/api/live-sync/lifecycle/{superseded|retry-recovery|dead-letter}/{entityType}/{recordKey}` | Run a controlled indexing infrastructure canary. |
| `POST` | `/api/live-sync/search` | Search all three vector spaces with workspace filtering. |
| `POST` | `/api/live-sync/chat` | Retrieve synchronized evidence and generate a typed chat result. |
| `GET` | `/api/live-sync/manifest` | Describe the demo contract and confirm actions are disabled. |
| `GET` | `/api/demo/health` | Report runtime and deployed build identity. |

Indexing work and the workspace-to-work audit link are stored in JDBC and survive application
restart. Expired workspaces are removed by a scheduled cleanup that calls the same annotated delete
services, preventing stale demo vectors and clearing only that workspace's demo audit state.

## Run Without A Provider Key

From `examples/real-apps`:

```bash
mvn -B --no-transfer-progress -pl ai-fabric-live-data-sync -am package

CORS_ALLOWED_ORIGINS=http://127.0.0.1:8084 \
java -jar ai-fabric-live-data-sync/target/ai-fabric-live-data-sync-1.0.0-SNAPSHOT.jar \
  --spring.profiles.active=smoke \
  --server.port=8104
```

The smoke profile is for deterministic lifecycle and UI verification. It is not evidence of live
LLM intelligence.

## Run With OpenAI

```bash
OPENAI_ENABLED=true \
OPENAI_API_KEY='<secret>' \
OPENAI_MODEL=gpt-4o-mini \
OPENAI_EMBEDDING_MODEL=text-embedding-3-small \
OPENAI_EMBEDDING_DIMENSIONS=512 \
APP_DEMO_REQUIRE_REAL_AI=true \
CORS_ALLOWED_ORIGINS=http://127.0.0.1:8084 \
java -jar ai-fabric-live-data-sync/target/ai-fabric-live-data-sync-1.0.0-SNAPSHOT.jar
```

Do not commit `OPENAI_API_KEY`. `APP_DEMO_REQUIRE_REAL_AI=true` rejects smoke-like generation in a
deployed environment instead of hiding provider misconfiguration.

## Verify The Lifecycle

```bash
base=http://localhost:8104
workspace=$(curl -fsS -X POST "$base/api/live-sync/workspaces" | jq -r .workspaceId)

curl -fsS "$base/api/live-sync/state" \
  -H "X-Demo-Workspace-ID: $workspace" | jq '{sourceTotal, vectorTotal, synchronizedTotal}'

curl -fsS -X PUT "$base/api/live-sync/entities/products/novabook-air" \
  -H "X-Demo-Workspace-ID: $workspace" \
  -H 'Content-Type: application/json' \
  -d '{
    "title":"NovaBook Air",
    "summary":"A lightweight 14-inch notebook for mobile teams.",
    "specification":"The upgraded battery is rated for 26 hours.",
    "category":"Laptops",
    "price":1399,
    "status":"PUBLISHED"
  }' | jq '.mutation, .state.entities[] | select(.recordKey == "novabook-air")'

curl -fsS -X DELETE "$base/api/live-sync/entities/policies/opened-electronics-return" \
  -H "X-Demo-Workspace-ID: $workspace" | jq '{sourceTotal: .state.sourceTotal, vectorTotal: .state.vectorTotal}'

work=$(curl -fsS -X POST \
  "$base/api/live-sync/lifecycle/retry-recovery/guides/amber-synclight" \
  -H "X-Demo-Workspace-ID: $workspace" | jq -r .indexingWork.workId)

curl -fsS "$base/api/live-sync/indexing-work/$work" \
  -H "X-Demo-Workspace-ID: $workspace" | jq '{workId, status, retryCount}'
```

Expected progression:

1. Seed state is `6` database rows, `6` vectors, and `6` aligned revisions.
2. Product revision becomes `2`; vector content contains `26 hours` and not `18 hours`.
3. Policy deletion leaves `5` rows and `5` vectors.
4. A follow-up chat answer can use only the current five vectors.
5. The retry canary keeps one work ID, records one retry, and reaches `COMPLETED`.

## Tests

```bash
mvn -B --no-transfer-progress -pl ai-fabric-live-data-sync -am test
```

The test suite preserves:

- annotation presence and lifecycle configuration on all entity types;
- seed, update, delete, and independent database/vector revision behavior;
- browser-workspace isolation;
- workspace-bound indexing work status authorization;
- source-version supersession, same-ID retry recovery, and exhausted dead-letter behavior;
- durable app-side work references;
- the reusable chat UI response shape;
- visible provider failure with no fake fallback.

## Docker

Build context must be `examples/real-apps`:

```bash
docker build \
  -f ai-fabric-live-data-sync/Dockerfile \
  --build-arg AI_FABRIC_VERSION=0.5.3 \
  -t ai-fabric-live-data-sync:0.5.3 .
```

Framework contributors can also build the tested local JAR and exercise the same runtime image
directly, without changing the default Maven Central consumer build:

```bash
mvn -B --no-transfer-progress \
  -pl ai-fabric-live-data-sync -am clean verify

docker build \
  --target release-candidate \
  --build-context \
    release-candidate-artifact=./ai-fabric-live-data-sync/target \
  -f ai-fabric-live-data-sync/Dockerfile \
  --build-arg AI_FABRIC_VERSION=0.5.3 \
  --build-arg SOURCE_COMMIT="$(git rev-parse HEAD)" \
  --build-arg SOURCE_BRANCH="$(git branch --show-current)" \
  -t ai-fabric-live-data-sync:0.5.3-rc .
```

The `release-candidate` target is retained as a source-artifact verification path. The default
`runtime` target performs a clean Maven build against the published Maven Central artifacts.

Deployment settings:

```text
Base directory: /examples/real-apps
Dockerfile: /ai-fabric-live-data-sync/Dockerfile
Exposed port: 8104
```

Recommended environment:

```text
PORT=8104
OPENAI_ENABLED=true
OPENAI_API_KEY=<secret>
OPENAI_MODEL=gpt-4o-mini
OPENAI_EMBEDDING_MODEL=text-embedding-3-small
OPENAI_EMBEDDING_DIMENSIONS=512
APP_DEMO_REQUIRE_REAL_AI=true
CORS_ALLOWED_ORIGINS=https://ai-fabric.dev
JAVA_OPTS=-Xms256m -Xmx768m
```
