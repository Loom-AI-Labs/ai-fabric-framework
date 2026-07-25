# Cloud Qdrant + OpenAI Vector Search

## Scenario

This app demonstrates a production-like semantic search stack using Postgres for domain storage,
Qdrant as an external vector database, and OpenAI embeddings.

It is intentionally simple: domain entities define indexing intent through annotations and
configuration, then app writes call AI Fabric indexing/search APIs.

## AI Fabric Capabilities Proved

- Real external embedding provider wiring with OpenAI.
- Real external vector database wiring with Qdrant gRPC.
- Postgres-backed domain storage.
- Annotation-assisted indexing using `@AICapable`, `@AISearchable`, and `@AIContext`.
- Config-driven indexing/search through `ai-entity-config.yml`.
- Transaction-aware index-on-write behavior through `@AIProcess` and
  `AIEntityIndexingGateway`.
- Semantic search through `AICoreService.performSearch`.

## Framework Surfaces

- `ai-fabric-starter`
- Qdrant vector provider
- OpenAI embedding provider path
- annotation/config indexing
- Spring Boot JPA/Postgres integration

## Runtime Posture

This is not a no-key smoke app. Full runtime validation requires:

- Docker or equivalent services for Postgres and Qdrant
- OpenAI API key

Use this app for cloud/provider wiring evidence, not default local CI.

## Prerequisites

- Docker
- OpenAI API key
- Local framework artifacts installed

## Run

Install framework artifacts:

```bash
mvn -B -V --no-transfer-progress -f ai-infrastructure-module/pom.xml install
```

Start Postgres and Qdrant:

```bash
cd examples/real-apps/cloud-qdrant-openai-vector-search
docker compose up -d
```

If the Qdrant image or client changed, recreate volumes:

```bash
cd examples/real-apps/cloud-qdrant-openai-vector-search
docker compose down -v
docker compose up -d
```

Run the app:

```bash
export AI_PROVIDERS_OPENAI_API_KEY="..."
mvn -B -V --no-transfer-progress package
java -jar target/cloud-qdrant-openai-vector-search-1.0.0-SNAPSHOT.jar
```

Default port: `8098`.

## Validate

Use `requests/demo.http` to run the scenario:

1. Create or update domain records.
2. Trigger AI Fabric indexing.
3. Search through the semantic endpoint.
4. Confirm results come from Qdrant-backed vector search.

Focused build/test command:

```bash
mvn -B -V --no-transfer-progress -f examples/real-apps/pom.xml -pl cloud-qdrant-openai-vector-search -am test
```

## Configuration

OpenAI:

- `AI_PROVIDERS_OPENAI_API_KEY`

Qdrant:

- `AI_PROVIDERS_QDRANT_HOST`, default `localhost`
- `AI_PROVIDERS_QDRANT_GRPC_PORT`, default `6334`
- `AI_PROVIDERS_QDRANT_API_KEY`, required only for secured/Qdrant Cloud deployments

## What This App Does Not Cover

- No-key/offline release smoke. Use `smoke-support` and local apps.
- Vector lifecycle/admin readiness comparison. Use `vector-readiness-playground`.
- RAG quality gates. Use `smart-faq-assistant`.
