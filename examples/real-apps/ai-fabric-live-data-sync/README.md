# AI Fabric Live Data Sync

This real app proves that ordinary Spring Boot/JPA entity changes can remain synchronized with the
vector evidence used by retrieval and an LLM. The application code saves or deletes domain rows;
AI Fabric annotations own the indexing lifecycle.

No action capability is enabled. The scope is deliberately limited to:

- annotated field extraction;
- create, update, and delete synchronization;
- metadata-filtered retrieval;
- evidence-grounded generation;
- database/vector consistency proof.

## What The Demo Proves

The demo creates an isolated browser workspace with two records for each of three entity types:

| Entity type | Example evidence |
| --- | --- |
| `sync-product` | NovaBook Air battery and hardware details |
| `sync-policy` | Opened-electronics return window |
| `sync-guide` | Amber SyncLight recovery steps |

The public UI can edit or delete any row. After each normal service call, it independently reads the
database state and vector state to prove:

- updated searchable text replaces the previous vector content;
- `@AIContext` metadata contains the workspace and current entity revision;
- deleted rows no longer have vectors;
- RAG and the LLM see only vectors in the current browser workspace.

The live chat uses the independently published
[`@loom-ai-labs/ai-fabric-chat-ui`](https://github.com/Loom-AI-Labs/ai-fabric-chat-ui)
package. The backend returns its typed result contract and does not substitute a deterministic
answer when the live provider fails.

## Annotation Contract

| Annotation | Demo use |
| --- | --- |
| `@EnableAIInfrastructure` | Activates AI Fabric in `LiveDataSyncApplication`. |
| `@AICapable` | Declares the three entity types, synchronous lifecycle policy, enabled AI features, and migration repository. |
| `@AISearchable` | Selects weighted, preprocessed text embedded into each vector. |
| `@AIContext` | Adds identifiers, workspace scope, status, dates, prices, and revision metadata without embedding those fields. |
| `@AIProcess` | Observes create, update, and delete service results and routes them through AI Fabric indexing. |

These are all annotations in the current framework that participate in annotation-driven entity
sync. `@AISmartValidation` is a separate validation feature and action annotations are intentionally
outside this demo.

The service method must return the created, updated, or deleted entity. In particular, delete
methods return the entity after deleting it so the AI Fabric aspect retains the identity needed to
remove its vector.

## Data And Request Flow

```text
React demo / reusable Chat UI
        |
        | normal PUT or DELETE
        v
Spring MVC controller
        |
        v
JPA domain service + H2 source row
        |
        | returned entity observed by @AIProcess
        v
AICapableAspect -> IndexingCoordinator
        |
        | @AISearchable content + @AIContext metadata
        v
Lucene vector store
        |
        | exact workspaceId metadata filter
        v
AI Fabric retrieval -> Spring AI OpenAI generation
```

The controllers never call an indexing endpoint. `DemoStateService` reads H2 and Lucene separately
only to display lifecycle proof.

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
| `PUT` | `/api/live-sync/entities/{products|policies|guides}/{recordKey}` | Update a normal JPA entity and synchronize its vector. |
| `DELETE` | `/api/live-sync/entities/{products|policies|guides}/{recordKey}` | Delete the source entity and its vector. |
| `POST` | `/api/live-sync/search` | Search all three vector spaces with workspace filtering. |
| `POST` | `/api/live-sync/chat` | Retrieve synchronized evidence and generate a typed chat result. |
| `GET` | `/api/live-sync/manifest` | Describe the demo contract and confirm actions are disabled. |
| `GET` | `/api/demo/health` | Report runtime and deployed build identity. |

Expired workspaces are removed by a scheduled cleanup that calls the same annotated delete
services, preventing stale demo vectors.

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
```

Expected progression:

1. Seed state is `6` database rows, `6` vectors, and `6` aligned revisions.
2. Product revision becomes `2`; vector content contains `26 hours` and not `18 hours`.
3. Policy deletion leaves `5` rows and `5` vectors.
4. A follow-up chat answer can use only the current five vectors.

## Tests

```bash
mvn -B --no-transfer-progress -pl ai-fabric-live-data-sync -am test
```

The test suite preserves:

- annotation presence and lifecycle configuration on all entity types;
- seed, update, delete, and independent database/vector revision behavior;
- browser-workspace isolation;
- the reusable chat UI response shape;
- visible provider failure with no fake fallback.

## Docker

Build context must be `examples/real-apps`:

```bash
docker build \
  -f ai-fabric-live-data-sync/Dockerfile \
  --build-arg AI_FABRIC_VERSION=0.3.3 \
  -t ai-fabric-live-data-sync:0.3.3 .
```

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
