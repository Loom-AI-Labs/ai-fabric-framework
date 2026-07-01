# Customer Runtime Demo

## Scenario

This app models a customer-owned domain system that emits records and governed actions through AI
Fabric runtime-style contracts.

It intentionally avoids the platform relay. The goal is to show the portable framework boundary:
customer domain data can become AI Fabric data-sync payloads, retrieval can be tenant-scoped, and
write actions can require confirmation before execution.

## AI Fabric Capabilities Proved

- Domain create/update/delete operations produce AI Fabric data-sync upsert/delete payloads.
- Tenant-scoped search returns only the caller tenant's records.
- Write actions require confirmation before the customer connector mutates domain state.
- Connector outage is returned as a structured failure rather than a raw HTTP/client exception.
- Customer-owned domain logic stays separate from the AI Fabric runtime boundary.

## Framework Surfaces

- `ai-fabric-data-sync` DTO contracts
- action connector execution shape
- action confirmation policy
- tenant metadata and retrieval scoping
- structured connector failure handling

## Runtime Posture

This app runs entirely local:

- in-memory customer domain fixture
- deterministic test data
- no model keys
- no vector DB service
- no external connector process

## Run

From the repository root:

```bash
mvn -B -V --no-transfer-progress -f examples/real-apps/pom.xml -pl customer-runtime-demo -am package
java -jar examples/real-apps/customer-runtime-demo/target/customer-runtime-demo-1.0.0-SNAPSHOT.jar \
  --spring.profiles.active=smoke
```

## Validate

Focused tests:

```bash
mvn -B -V --no-transfer-progress -f examples/real-apps/pom.xml -pl customer-runtime-demo -am test
```

Use `requests/demo.http` to run the scenario.

## Demo Flow

1. Create a customer-domain record.
2. Inspect the generated data-sync upsert payload.
3. Search records as a tenant-scoped caller.
4. Attempt a write action and receive confirmation-required evidence.
5. Confirm the action and observe the domain fixture mutation.
6. Delete a record and inspect the generated data-sync delete payload.
7. Toggle connector availability and verify structured outage behavior.

## What This App Does Not Cover

- DB-backed action publication. Use `db-action-registry-lab`.
- Full ecommerce runtime proof. Use `ecommerce-store` with `chat-capabilities-demo`.
- Live connector service deployment. That belongs to platform/customer infrastructure.
