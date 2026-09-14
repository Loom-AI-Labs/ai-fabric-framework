# AI Fabric Incident Investigation Room

This real application is the reference proof for AI Fabric's bounded
multi-specialist chain runtime. A user asks one natural incident question. An
exact-version OpenAI manager may complete, clarify, consult one read-only
specialist, consult two independent specialists in parallel, adapt after a
projected result, or end with one approved read-only handoff. AI Fabric validates
every proposed transition and persists the chain before returning a result.

Each worker chooses only its approved operational READ actions, receives
backend-authorized incident candidates, and cites relevant evidence. The
application validates every event, runbook, tenant, deployment, and source
revision before exposing a safe projection to the manager or caller.

The demo preserves fixed sequential and parallel plans, standalone one-hop
delegation and handoff, and the bounded one-worker conversation manager as
developer comparison tools. It has no recursive workers, model-generated
graph, keyword router, or fallback intelligence.

## What It Proves

- exact v2 intake, service-health, change-risk, and conversation-manager
  specialist manifests while the original v1 definitions remain registered;
- typed schemas, prompt profiles, exact versions, and content hashes;
- `BOUNDED_ITERATIVE` worker execution with specialist-scoped READ actions;
- model selection among `read_service_metrics`, `read_incident_alerts`,
  `read_recent_deployments`, and `read_change_approvals`;
- server-owned tenant, deployment, incident, subject, scope, and source
  revision parameters;
- realistic relevant, stale, contradictory, and cross-boundary event records;
- model-selected event citations from the authorized candidate set;
- tenant/deployment-scoped `incident-runbook` RAG through Lucene;
- application validation of action provenance, event citations, runbook
  citations, boundaries, and source revision;
- fixed sequential and bounded parallel plans with `ALL_REQUIRED` fan-in;
- whole-plan failure when a required source is unavailable, with no partial or
  synthetic assessment;
- one-level delegation and handoff with a denied second-transition canary;
- backend-owned chat history, newest-message-only browser requests, follow-up
  routing, and idempotent replay;
- an application-selected `incident-smart-investigation@1` chain whose manager
  chooses zero, one, sequential, parallel, or terminal-handoff work from a
  closed exact-version target catalog;
- application-owned worker input mappers and safe result projectors;
- exact final-answer attribution through `supportingResultIds`, with no prose
  or keyword matching;
- durable encrypted JDBC requests, checkpoints, results, scoped replay, worker
  leases, restart recovery, cleanup, status, and cancellation; and
- independent provider, specialist, plan, action, runbook, event-source,
  storage, version, and build health.

## Responsibility Boundaries

The model owns proposals within registered boundaries:

1. The chain manager proposes whether to complete, clarify, invoke one worker,
   invoke independent workers in parallel, or request one terminal handoff.
2. A worker chooses the smallest useful subset of its approved READ actions.
3. The worker selects relevant event and runbook IDs from returned evidence.
4. The worker produces a typed assessment and a concise selection reason.
5. The manager synthesizes only from the application-approved projected
   results and must cite every result ID it used.

The application owns authority and safety:

1. It creates the session and binds the incident workspace.
2. It supplies trusted identity, tenant, deployment, scopes, and revision.
3. It filters source records and excludes cross-boundary records.
4. It selects the chain and declares its manager, exact target catalog,
   parallel eligibility, limits, and transition depth.
5. It maps typed worker requests and projects bounded worker results.
6. It rejects unsupported citations, changed definitions, mismatched revisions,
   or unapproved targets before projection.

## Data Flow

```text
browser question only
  -> server-owned incident session
  -> application selects incident-smart-investigation@1
  -> durable chain request and trusted-context fingerprints are persisted
  -> OpenAI manager returns one typed, schema-constrained directive
  -> AI Fabric validates target, policy, type, budget, deadline, and authority
  -> worker requests allowlisted READ actions and optional scoped runbook RAG
  -> backend applies trusted incident/deployment/revision filters
  -> Java validates and projects bounded facts and evidence IDs
  -> manager may adapt, fan out independently, or complete
  -> final response must attribute every available projected result ID
  -> protected terminal result and safe decision timeline reach the UI
```

The manager receives approved boundary context and target descriptions, not
full event payloads or worker tools. A worker receives operational evidence
only through actions it chose during that invocation. Workers never receive
sibling results or conversation history.

The two worker purposes deliberately separate ordinary orchestration actions
from specialist grounding reads. `incident-health` and `incident-change` set
`actions-enabled: false`, so a worker request cannot be reclassified as a
normal application write/action intent. Their `read-action-resolution` blocks
remain enabled and independently allowlist the operational READ actions that a
specialist may request. `incident-change` additionally enables only the
`incident-runbook` vector space and uses parallel action/RAG cooperation.

## Scenarios

| Scenario | Purpose |
| --- | --- |
| `checkout-regression` | Correlated health degradation, release evidence, approvals, distractors, and a scoped rollback runbook. |
| `inventory-pressure` | Saturation and inventory symptoms with a different change history. |
| `no-material-change` | Proves the model can report that no supported recent change explains the incident. |
| `ambiguous-symptom` | Exercises evidence selection among less decisive observations. |
| `branch-failure` | Makes required change sources unavailable and proves fail-closed plan fan-in. |

The Smart Investigation prompt chips additionally prove health-only routing,
adaptive selection, independent parallel selection, clarification, completion
without a worker, terminal handoff, invented-target denial, exact replay, and
trusted-boundary attack rejection.

## API Flow

List scenarios and create a server-owned session:

```bash
curl -s http://localhost:8107/api/incidents/scenarios

curl -s -X POST http://localhost:8107/api/incidents/sessions \
  -H 'Content-Type: application/json' \
  -d '{"scenarioId":"checkout-regression"}'
```

Use the returned ID as both the URL ID and the demo-session header. Generate a
new idempotency key for each new logical operation. Run the primary smart path:

```bash
SESSION_ID='<returned-session-id>'
REQUEST_KEY="$(uuidgen)"

curl -s -X POST \
  "http://localhost:8107/api/incidents/sessions/${SESSION_ID}/smart-investigations" \
  -H "X-AI-Fabric-Demo-Session: ${SESSION_ID}" \
  -H "Idempotency-Key: ${REQUEST_KEY}" \
  -H 'Content-Type: application/json' \
  -d '{"question":"Checkout degraded after deployment. Investigate health and change risk."}'
```

Available execution endpoints:

```text
GET    /api/incidents/sessions/{id}
POST   /api/incidents/sessions/{id}/reset
DELETE /api/incidents/sessions/{id}
POST   /api/incidents/sessions/{id}/smart-investigations
POST   /api/incidents/sessions/{id}/smart-investigations/async
GET    /api/incidents/sessions/{id}/smart-investigations/{executionId}
POST   /api/incidents/sessions/{id}/smart-investigations/{executionId}/cancel
POST   /api/incidents/sessions/{id}/plans/sequential
POST   /api/incidents/sessions/{id}/plans/parallel
POST   /api/incidents/sessions/{id}/compare
POST   /api/incidents/sessions/{id}/delegations
POST   /api/incidents/sessions/{id}/handoffs
POST   /api/incidents/sessions/{id}/manager/turns
```

Public request bodies contain only a question. They cannot select identity,
provider, specialist authority, action scope, topology, incident binding, or
evidence.

## Run Tests

From the repository root, run the ordinary reactor gate with tests enabled:

```bash
mvn -f examples/real-apps/pom.xml \
  -pl incident-investigation-room -am clean verify
```

The smoke profile uses `incident-smoke`, a deterministic structured-output
fixture for offline contract testing. It is never registered in the live
profile and cannot hide an OpenAI failure.

Run the real-provider scenarios separately:

```bash
export OPENAI_API_KEY='<secret>'

mvn -f examples/real-apps/pom.xml \
  -pl incident-investigation-room -am \
  -Dtest=IncidentInvestigationRealApiIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

The real suite verifies scoped direct and generated RAG, single-worker,
adaptive, parallel, clarification, no-worker, no-material-change, handoff,
invented-target, exact-replay, cross-boundary, plan, and history-backed flows.
It asserts contracts and observable choices rather than exact prose.

## Run Offline

From `examples/real-apps`:

```bash
mvn -pl incident-investigation-room -am spring-boot:run \
  -Dspring-boot.run.profiles=smoke
```

## Run With OpenAI

```bash
export OPENAI_ENABLED=true
export OPENAI_API_KEY='<secret>'
export OPENAI_MODEL=gpt-4o-mini
export OPENAI_EMBEDDING_MODEL=text-embedding-3-small
export OPENAI_EMBEDDING_DIMENSIONS=512
export AI_SPECIALIST_CHAIN_ENCRYPTION_SECRET='<stable-random-secret-32+-chars>'
export AI_SPECIALIST_CHAIN_FINGERPRINT_SECRET='<different-stable-secret-32+-chars>'

mvn -f examples/real-apps/pom.xml \
  -pl incident-investigation-room -am spring-boot:run
```

Inspect runtime readiness at `GET /api/demo/health`. A selected but unavailable
provider or source is reported as unavailable; the application does not switch
to smoke output.

## Deployment

After `0.6.1` is published, build the release image from the repository root.
It resolves the immutable AI Fabric release from Maven Central and does not
compile framework source from this checkout:

```bash
docker build \
  -f examples/real-apps/incident-investigation-room/Dockerfile \
  --build-arg AI_FABRIC_VERSION=0.6.1 \
  --build-arg SOURCE_COMMIT="$(git rev-parse HEAD)" \
  --build-arg SOURCE_BRANCH="$(git branch --show-current)" \
  -t ai-fabric-incident-investigation-room:0.6.1 .
```

Before publication, CI uses `Dockerfile.candidate` only after the framework and
real-app reactors install the candidate JARs locally. That image proves the
packaged source candidate and must not be presented as Maven Central consumer
proof.

Live environment:

```text
PORT=8107
OPENAI_ENABLED=true
OPENAI_API_KEY=<secret>
OPENAI_MODEL=gpt-4o-mini
OPENAI_EMBEDDING_MODEL=text-embedding-3-small
OPENAI_EMBEDDING_DIMENSIONS=512
AI_SPECIALIST_CHAINS_DURABLE_ENABLED=true
AI_SPECIALIST_CHAINS_ALLOW_EPHEMERAL=false
AI_SPECIALIST_CHAINS_INITIALIZE_SCHEMA=false
AI_SPECIALIST_CHAIN_ENCRYPTION_SECRET=<stable-random-secret-at-least-32-characters>
AI_SPECIALIST_CHAIN_FINGERPRINT_SECRET=<different-stable-random-secret-at-least-32-characters>
SPRING_DATASOURCE_URL=jdbc:postgresql://<host>:5432/<database>
SPRING_DATASOURCE_DRIVER_CLASS_NAME=org.postgresql.Driver
SPRING_DATASOURCE_USERNAME=<database-user>
SPRING_DATASOURCE_PASSWORD=<database-secret>
CORS_ALLOWED_ORIGINS=https://ai-fabric.dev
JAVA_OPTS=-Xms256m -Xmx768m
```

The self-contained demo default sets `AI_SPECIALIST_CHAINS_INITIALIZE_SCHEMA`
to `true`. A production deployment must install the reviewed
`ai_specialist_chain_execution` Flyway or Liquibase migration first and set it
to `false`. The two chain secrets must be stable across restarts and different
from each other. Rotating them without a migration makes protected retained
executions unreadable.

Mount `/app/data` on persistent storage when using the default file H2 and
Lucene configuration. H2 stores AI Fabric chat sessions, durable specialist
chain checkpoints/results, and the app-owned mapping from an opaque demo
session to its conversation and scenario. Lucene stores the deterministic
runbook index. Event fixtures are immutable application data and are rebuilt
identically at startup.

Fixed plans and standalone delegation/handoff executions remain explicitly
ephemeral. The bounded Smart Investigation chain is the durable execution path,
and `GET /api/demo/health` must report `storage.specialistChains=JDBC` before a
deployment is described as restart-safe.

The persistent H2 database and Lucene index are single-process stores. A rolling
deployment must not start the replacement container while the previous container
still owns `/app/data`. For Coolify, stop the running application and then start
the new deployment. Keep the volume intact. Use PostgreSQL and an independently
managed vector store before introducing overlapping replicas.
