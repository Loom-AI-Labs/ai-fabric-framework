# AI Fabric Incident Investigation Room

This real application demonstrates bounded agentic investigation with AI Fabric
`0.5.3`. OpenAI routes a question to an exact-version specialist. That
specialist chooses approved operational READ actions, receives only
backend-authorized incident candidates, and cites the relevant evidence. The
application validates every event, runbook, tenant, deployment, and source
revision before a result reaches the caller.

The demo also composes the same workers through fixed sequential and parallel
plans, one-hop delegation and handoff, and a backend-owned conversation
manager. It has no model-generated topology and no fallback intelligence.

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
  routing, and idempotent replay; and
- independent provider, specialist, plan, action, runbook, event-source,
  storage, version, and build health.

## Responsibility Boundaries

The model owns decisions within registered boundaries:

1. Intake chooses zero or one approved specialist.
2. A worker chooses the smallest useful subset of its approved READ actions.
3. The worker selects relevant event and runbook IDs from returned evidence.
4. The worker produces a typed assessment and a concise selection reason.

The application owns authority and safety:

1. It creates the session and binds the incident workspace.
2. It supplies trusted identity, tenant, deployment, scopes, and revision.
3. It filters source records and excludes cross-boundary records.
4. It declares plan topology, transition depth, and exact capability lists.
5. It rejects unsupported citations or mismatched revisions before projection.

## Data Flow

```text
browser question
  -> server-owned incident session
  -> OpenAI intake selects an exact v2 specialist
  -> AI Fabric intersects the manifest with the application capability policy
  -> specialist requests one or more allowlisted READ actions
  -> backend applies trusted incident/deployment/revision filters
  -> action returns bounded candidates, including realistic distractors
  -> optional scoped runbook RAG runs for change investigations
  -> specialist selects and cites relevant evidence
  -> Java validates provenance, citations, boundaries, and revision
  -> safe typed result and decision trace reach the UI
```

The intake router receives boundary identifiers, not full event payloads. The
worker receives operational evidence only through actions it chose during that
invocation.

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

## API Flow

List scenarios and create a server-owned session:

```bash
curl -s http://localhost:8107/api/incidents/scenarios

curl -s -X POST http://localhost:8107/api/incidents/sessions \
  -H 'Content-Type: application/json' \
  -d '{"scenarioId":"checkout-regression"}'
```

Use the returned ID as both the URL ID and the demo-session header. Generate a
new idempotency key for each new operation:

```bash
SESSION_ID='<returned-session-id>'
REQUEST_KEY="$(uuidgen)"

curl -s -X POST \
  "http://localhost:8107/api/incidents/sessions/${SESSION_ID}/delegations" \
  -H "X-AI-Fabric-Demo-Session: ${SESSION_ID}" \
  -H "Idempotency-Key: ${REQUEST_KEY}" \
  -H 'Content-Type: application/json' \
  -d '{"question":"What changed shortly before checkout failed?"}'
```

Available execution endpoints:

```text
GET    /api/incidents/sessions/{id}
POST   /api/incidents/sessions/{id}/reset
DELETE /api/incidents/sessions/{id}
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

The real suite verifies scoped direct and generated RAG, health routing,
change routing, both required plan branches, cross-boundary exclusion, and a
history-backed follow-up. It asserts contracts and observable choices rather
than exact prose.

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

mvn -f examples/real-apps/pom.xml \
  -pl incident-investigation-room -am spring-boot:run
```

Inspect runtime readiness at `GET /api/demo/health`. A selected but unavailable
provider or source is reported as unavailable; the application does not switch
to smoke output.

## Deployment

Build from the repository root. The image resolves the immutable AI Fabric
release from Maven Central and does not compile framework source from this
checkout:

```bash
docker build \
  -f examples/real-apps/incident-investigation-room/Dockerfile \
  --build-arg AI_FABRIC_VERSION=0.5.3 \
  --build-arg SOURCE_COMMIT="$(git rev-parse HEAD)" \
  --build-arg SOURCE_BRANCH="$(git branch --show-current)" \
  -t ai-fabric-incident-investigation-room:0.5.3 .
```

Live environment:

```text
PORT=8107
OPENAI_ENABLED=true
OPENAI_API_KEY=<secret>
OPENAI_MODEL=gpt-4o-mini
OPENAI_EMBEDDING_MODEL=text-embedding-3-small
OPENAI_EMBEDDING_DIMENSIONS=512
CORS_ALLOWED_ORIGINS=https://ai-fabric.dev
JAVA_OPTS=-Xms256m -Xmx768m
```

Mount `/app/data` on persistent storage when chat history must survive restart.
H2 stores AI Fabric chat sessions and turns together with the app-owned mapping
from an opaque demo session to its conversation and scenario. That binding lets
the same public session reload its backend-owned history after an application
restart. Lucene stores the deterministic runbook index in the same data
directory. Event fixtures are immutable application data and are rebuilt
identically at startup. Plan, delegation, and handoff executions remain
explicitly ephemeral in AI Fabric `0.5.3`; health and the UI label that behavior
instead of implying durable plan recovery.
