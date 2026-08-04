# AI Fabric Deployment Knowledge Guard

This source-candidate app proves the AI Fabric `0.5.x` specialist boundary with
real vector retrieval. A browser chooses one server-approved demo context. The
backend binds its tenant and deployment IDs into `TrustedExecutionContext`, and
the framework enforces those values as Lucene metadata filters.

The dataset deliberately repeats titles such as `Current deployment status`
across tenants and deployments. The specialist can only answer from evidence in
the active boundary. User text cannot select another tenant or deployment.

## Capabilities

- manifest-defined `deployment-knowledge-reader@1` specialist
- structured, citation-validated output
- OpenAI generation and embeddings in the live profile
- deterministic smoke providers for local tests
- Lucene metadata-filtered retrieval
- session-isolated, server-owned deployment context
- cross-tenant, cross-deployment, identity-spoof, and missing-vector-scope canaries
- visible provider/specialist/index/build health
- no actions, writes, or hidden fallback answers

## Run Tests

From `examples/real-apps`:

```bash
mvn -pl deployment-knowledge-guard -am test
```

## Run Offline

```bash
mvn -pl deployment-knowledge-guard -am spring-boot:run \
  -Dspring-boot.run.profiles=smoke
```

## Run With OpenAI

```bash
export OPENAI_ENABLED=true
export OPENAI_API_KEY=your-key
export OPENAI_MODEL=gpt-4o-mini
export OPENAI_EMBEDDING_MODEL=text-embedding-3-small
export OPENAI_EMBEDDING_DIMENSIONS=512
mvn -pl deployment-knowledge-guard -am spring-boot:run
```

Create a session with `POST /api/deployment-guard/sessions`, then pass the
returned opaque value through `X-AI-Fabric-Demo-Session`. The public request body
contains only the operator's question.

## Deployment

Build from the repository root:

```bash
docker build \
  -f examples/real-apps/deployment-knowledge-guard/Dockerfile \
  -t ai-fabric-deployment-knowledge-guard:source .
```

Required live environment:

```text
PORT=8106
OPENAI_ENABLED=true
OPENAI_API_KEY=<secret>
OPENAI_MODEL=gpt-4o-mini
OPENAI_EMBEDDING_MODEL=text-embedding-3-small
OPENAI_EMBEDDING_DIMENSIONS=512
CORS_ALLOWED_ORIGINS=https://ai-fabric.dev
JAVA_OPTS=-Xms256m -Xmx768m
```
