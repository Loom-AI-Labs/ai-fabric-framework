# Vector Readiness Playground

## Scenario

This app demonstrates vector provider lifecycle/admin readiness. It runs a small store/existence/delete
check and exposes readiness evidence for the configured `VectorDatabaseService`.

Use this app to explain why AI Fabric's vector contract is more than similarity search: release
readiness also needs diagnostics, lifecycle operations, and provider capability evidence.

## AI Fabric Capabilities Proved

- Readiness endpoint exposes `READY`, `WARN`, or `NOT_READY`.
- Diagnostics include provider capability evidence from `VectorDatabaseService.adminDiagnostics()`.
- Lifecycle run stores a vector with metadata.
- Lifecycle run checks vector existence.
- Lifecycle run deletes the vector.
- Default smoke path can use the in-memory provider.

## Framework Surfaces

- `VectorDatabaseService`
- vector provider lifecycle/admin API
- provider diagnostics
- vector metadata evidence
- smoke-profile provider selection

## Runtime Posture

Default runtime:

- in-memory vector provider under smoke profile
- no external vector DB
- no model keys
- deterministic lifecycle scenario

Provider-specific profiles can be layered in when validating Qdrant, Weaviate, Milvus, Pinecone, or
other vector providers.

## Run

From the repository root:

```bash
mvn -B -V --no-transfer-progress -f examples/real-apps/pom.xml -pl vector-readiness-playground -am package
java -jar examples/real-apps/vector-readiness-playground/target/vector-readiness-playground-1.0.0-SNAPSHOT.jar \
  --spring.profiles.active=smoke
```

## Validate

Focused tests:

```bash
mvn -B -V --no-transfer-progress -f examples/real-apps/pom.xml -pl vector-readiness-playground -am test
```

Use `requests/demo.http` to run readiness and lifecycle checks.

## Demo Flow

1. Check vector provider readiness.
2. Inspect admin diagnostics.
3. Run lifecycle check.
4. Verify store, existence, delete, and metadata evidence.
5. Review readiness status if provider operations are missing or degraded.

## What This App Does Not Cover

- RAG quality. Use `smart-faq-assistant`.
- Real cloud vector setup. Use `cloud-qdrant-openai-vector-search` or provider contract tests.
- Tenant/shared vector storage verification. Use platform/vector provider verification suites.
