# DB Action Registry Lab

## Scenario

This app proves the DB-backed connector action registry lifecycle in a realistic, operator-controlled
flow.

A customer-owned support-ticket fixture exposes two possible action templates. An operator proposes
one, approves it into AI Fabric's DB action registry, refreshes runtime discovery, executes the action
through the connector handler path, and later deregisters it.

The fixture is local, but the registry, entity/repository/service/controller stack, and connector
action handler path are real AI Fabric framework code.

## AI Fabric Capabilities Proved

- DB-backed action registration stores validated `ConnectorActionDefinition` contracts.
- Approval is explicit before a proposed action becomes executable.
- Runtime discovery comes from `AIActionRegistry` after DB publication.
- Connector actions execute through `ConnectorAIActionHandler`.
- Read actions execute directly.
- Write-like actions require confirmation before mutation.
- Deregistration removes the action from both DB catalog and runtime registry availability.
- Raw `/api/ai/actions/registry/**` endpoints are API-key protected.

## Framework Surfaces

- `ai-fabric-actions-registry`
- `ConnectorActionRegistryService`
- `AIActionRegistry`
- `ConnectorAIActionHandler`
- `ActionConnectorExecutor`
- action access modes and confirmation policy
- registry API-key protection
- H2/JPA persistence

## Runtime Posture

This app is offline and deterministic:

- local H2 DB
- in-app support-ticket fixture
- local `OutboundHttpExecutor` that routes connector calls into the fixture
- no external model
- no vector DB
- no external connector service

## Run

From the repository root:

```bash
mvn -B -V --no-transfer-progress -f examples/real-apps/pom.xml -pl db-action-registry-lab -am package
java -jar examples/real-apps/db-action-registry-lab/target/db-action-registry-lab-1.0.0-SNAPSHOT.jar
```

Default port: `8099`.

## Validate

Focused tests:

```bash
mvn -B -V --no-transfer-progress -f examples/real-apps/pom.xml -pl db-action-registry-lab -am test
```

Use `requests/demo.http` to run the lifecycle.

## Demo Flow

1. List controlled action templates.
2. Propose `ticket.lookup` or `ticket.escalate`.
3. Approve the proposal into the DB registry.
4. Discover DB and runtime actions.
5. Execute read action directly.
6. Attempt write action and receive confirmation-required evidence.
7. Execute write action after confirmation.
8. Call raw registry endpoint with `X-AIFABRIC-REGISTRY-KEY`.
9. Deregister action and verify it is no longer executable.

## Configuration

- `AI_ACTION_REGISTRY_KEY`: API key for `/api/ai/actions/registry/**`.
- Default local key: `local-registry-key`.

## What This App Does Not Cover

- File-based YAML connector action catalogs. That remains a separate connector-catalog path.
- Real external connector deployment.
- Natural-language action extraction by a live LLM.
