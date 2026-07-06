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

## Demo Backend App Architecture

The `aifabric` site includes a Smart FAQ Assistant demo page. That page is currently an explanatory UI
page, not a live browser client wired to this backend. This app is the runnable backend candidate for
turning that page into a live AI Fabric FAQ demo.

Backend dependencies:

- Spring Boot Web, Data JPA, Validation, Actuator, H2, and Lombok.
- AI Fabric modules: `ai-fabric-starter`, `ai-fabric-rag`, and `ai-fabric-vector-lucene`.
- `smoke-support` for shared release smoke and build metadata.

AI-enabled domain model:

- The FAQ flow is config-driven and intentionally uses no Java AI annotations.
- `ai-entity-config.yml` defines the `faq-article` entity type, searchable fields, embeddable fields,
  and metadata fields.
- `FaqArticleService` owns article CRUD, seeding, reindexing, semantic search, and optional ask flow.
- `FaqQualityService` owns golden questions and expected evidence checks so RAG quality can fail
  closed.

Providers and storage:

- Embeddings use the app's deterministic `SimpleHashEmbeddingProvider`.
- Vector search uses Lucene.
- H2 stores FAQ articles and golden-question fixtures.
- Optional answer generation can be enabled with `AI_FAQ_ENABLE_GENERATION=true` and
  `AI_FAQ_ENABLE_RAG=true` when an LLM provider is added/configured.

Request and data flow:

1. Seed FAQ articles through `POST /api/demo/seed`.
2. Reindex articles through `POST /api/demo/indexing/reindex/articles`; AI Fabric writes article
   evidence to the `faq-article` vector space.
3. Search calls `GET /api/faq/search`, which delegates to `AICoreService.performSearch`.
4. Ask calls `POST /api/faq/ask`; by default it returns grounded retrieval evidence without requiring
   an LLM.
5. Quality gates call `/api/faq/quality/golden/run` and verify expected source ids are retrieved
   before the app claims FAQ RAG readiness.

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
