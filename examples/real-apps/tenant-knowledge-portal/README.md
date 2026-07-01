# Tenant Knowledge Portal

## Scenario

This app demonstrates tenant-aware knowledge search, catalog inspection, role-limited actions, and
tenant deletion.

It is a focused proof that AI workflows can respect tenant and role boundaries even when documents
have overlapping titles or similar content.

## AI Fabric Capabilities Proved

- Same document title in two tenants returns only the caller tenant's result.
- Tenant metadata is used in retrieval and catalog evidence.
- Admin catalog visibility differs from regular user visibility.
- Cross-tenant action targets are rejected.
- Tenant deletion removes only the selected tenant's documents/catalog entries.
- Role-limited actions can be modeled as product workflows.

## Framework Surfaces

- tenant/context metadata
- metadata filters
- governance catalog pattern
- role-limited actions
- deletion lifecycle
- deterministic local smoke profile

## Runtime Posture

Default runtime is local and deterministic:

- H2/in-memory fixtures
- no external model keys
- no external vector service
- smoke profile supported

## Run

From the repository root:

```bash
mvn -B -V --no-transfer-progress -f examples/real-apps/pom.xml -pl tenant-knowledge-portal -am package
java -jar examples/real-apps/tenant-knowledge-portal/target/tenant-knowledge-portal-1.0.0-SNAPSHOT.jar \
  --spring.profiles.active=smoke
```

## Validate

Focused tests:

```bash
mvn -B -V --no-transfer-progress -f examples/real-apps/pom.xml -pl tenant-knowledge-portal -am test
```

Use `requests/demo.http` to run the tenant boundary scenario.

## Demo Flow

1. Seed documents for two tenants with overlapping titles.
2. Search as a tenant user and verify only that tenant's documents are returned.
3. Inspect catalog as a regular user.
4. Inspect catalog as an admin.
5. Attempt a cross-tenant action target and verify rejection.
6. Delete one tenant and verify the other tenant's data remains.

## What This App Does Not Cover

- Public browser token issuance. That is platform/runtime-auth territory.
- Live shared vector storage providers. Use provider/vector integration tests.
- Marketplace/shared-index data plugins. Those belong to platform verification.
