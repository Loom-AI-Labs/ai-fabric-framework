# AI Fabric Incident Investigation Room

This real app proves the bounded composition capabilities introduced in AI
Fabric `0.5.x`. Two exact-version, read-only specialists inspect independent
branches of one immutable incident snapshot. The application declares both a
sequential and a bounded parallel plan, then deterministically combines only
validated typed results and approved evidence IDs.

It does not expose a free-form graph builder. The application owns topology,
identity, source revisions, branch inputs, target allowlists, and result
projection.

## What It Proves

- manifest-defined `service-health-reader@1` and `change-risk-reader@1`
- structured input/output schemas and content hashes
- fixed `incident-investigation-sequential@1` plan
- bounded `incident-investigation-parallel@1` plan with `ALL_REQUIRED` fan-in
- deterministic citation validation against each immutable evidence branch
- explicit whole-plan failure without a synthetic partial assessment
- one-level delegation and handoff with rejected second-transition canaries
- bounded conversation manager with backend-owned chat history
- idempotent manager-turn replay
- server-created, session-isolated incident scenarios
- visible provider, specialist, plan, storage, version, and build health
- explicit provider and contract failures with no fallback intelligence

## API Flow

1. `GET /api/incidents/scenarios`
2. `POST /api/incidents/sessions` with `{"scenarioId":"checkout-regression"}`
3. Send the returned session ID as `X-AI-Fabric-Demo-Session`.
4. Use one of the bounded execution endpoints:

```text
POST /api/incidents/sessions/{id}/plans/sequential
POST /api/incidents/sessions/{id}/plans/parallel
POST /api/incidents/sessions/{id}/compare
POST /api/incidents/sessions/{id}/delegations
POST /api/incidents/sessions/{id}/handoffs
POST /api/incidents/sessions/{id}/manager/turns
```

Every execution request also requires an application-generated
`Idempotency-Key`. Public bodies contain only the incident question; they cannot
select identity, provider, specialist authority, topology, or evidence.

## Run Tests

From `examples/real-apps`:

```bash
mvn -pl incident-investigation-room -am test
```

The smoke profile uses a deterministic structured-output fixture solely for
offline contract and orchestration testing. It is visibly named
`incident-smoke` and is never enabled in the live profile.

## Run Offline

```bash
mvn -pl incident-investigation-room -am spring-boot:run \
  -Dspring-boot.run.profiles=smoke
```

## Run With OpenAI

```bash
export OPENAI_ENABLED=true
export OPENAI_API_KEY=your-key
export OPENAI_MODEL=gpt-4o-mini
mvn -pl incident-investigation-room -am spring-boot:run
```

## Deployment

Build from the repository root:

```bash
docker build \
  -f examples/real-apps/incident-investigation-room/Dockerfile \
  -t ai-fabric-incident-investigation-room:source .
```

Live environment:

```text
PORT=8107
OPENAI_ENABLED=true
OPENAI_API_KEY=<secret>
OPENAI_MODEL=gpt-4o-mini
CORS_ALLOWED_ORIGINS=https://ai-fabric.dev
JAVA_OPTS=-Xms256m -Xmx768m
```

Mount `/app/data` on persistent storage when backend chat history must survive
restart. Plans, delegation, and handoff are intentionally bounded and
ephemeral in AI Fabric `0.5.2`; the UI and health endpoint label that behavior
instead of claiming durable plan execution.
