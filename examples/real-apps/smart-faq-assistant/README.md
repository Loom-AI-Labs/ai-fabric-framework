# Smart FAQ Assistant

## Scenario

This app demonstrates offline semantic search over a curated FAQ knowledge base, with optional
context-based answer generation when an LLM provider is configured.

It also includes a local RAG quality workbench: seed the baseline FAQ corpus, run golden questions,
inspect retrieved evidence, and fail the gate when expected FAQ articles are not retrieved.

## AI Fabric Capabilities Proved

- Config-driven AI setup through `ai-entity-config.yml`.
- No AI annotations required for the core FAQ flow.
- H2 plus deterministic local embeddings plus Lucene vector search.
- FAQ seed, reindex, semantic search, and optional ask flow.
- RAG quality gate using golden questions and expected source evidence.
- Fail-closed quality behavior when retrieval throws or expected evidence is absent.
- Optional Spring AI-backed RAG evaluation when `ChatClient.Builder` is configured.

## Framework Surfaces

- `ai-fabric-starter`
- `ai-fabric-rag`
- `ai-fabric-vector-lucene`
- config-driven indexing
- `AICoreService.performSearch`
- optional Spring AI RAG evaluation helpers

## Runtime Posture

Default runtime:

- H2 database
- deterministic local embeddings
- Lucene vector DB
- no LLM provider required

The shared `smoke` profile is for no-key startup checks. The normal local profile is better for the
RAG quality workbench because it uses this app's Lucene plus `simple` embedding setup.

Default port: `8094`.

## Run

From the repository root:

```bash
mvn -B -V --no-transfer-progress -f examples/real-apps/pom.xml -pl smart-faq-assistant -am package
java -jar examples/real-apps/smart-faq-assistant/target/smart-faq-assistant-1.0.0-SNAPSHOT.jar
```

## Validate

Focused tests:

```bash
mvn -B -V --no-transfer-progress -f examples/real-apps/pom.xml -pl smart-faq-assistant -am test
```

Use `requests/demo.http` to run the full scenario.

## Demo Flow

1. Seed FAQ articles.
2. Reindex FAQ articles.
3. Run semantic search.
4. Ask a question in search-only mode.
5. Run the golden-question quality gate.
6. Optionally enable answer generation and evaluator-backed checks.

## Key Endpoints

- `POST /api/demo/seed`
- `POST /api/demo/indexing/reindex/articles`
- `POST /api/demo/quality/seed-and-run`
- `GET /api/faq/search?q=...&limit=...`
- `POST /api/faq/ask`
- `GET /api/faq/quality/golden`
- `POST /api/faq/quality/golden/run`

## Optional Answer Generation

By default the app runs without an LLM provider dependency. To enable contextual generation, add an
LLM provider module/configuration and set:

- `AI_FAQ_ENABLE_GENERATION=true`
- `AI_FAQ_ENABLE_RAG=true`

## Optional Spring AI RAG Evaluation

The golden gate runs locally by default and checks whether expected FAQ articles are retrieved.

If a Spring AI `ChatClient.Builder` is configured:

- set `ai.infrastructure.rag.evaluation.enabled=true`
- call `POST /api/faq/quality/golden/run`

Example request:

```json
{
  "threshold": 0.0,
  "springAiEvaluation": true
}
```

## What This App Does Not Cover

- Customer-owned retrieval connector boundary. That should be covered by a dedicated
  `retrieval-connector-boundary-lab`.
- Live provider fallback. Use `provider-failover-lab`.
- Vector provider lifecycle/admin readiness. Use `vector-readiness-playground`.
