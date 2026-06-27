# Smart FAQ Assistant (Real_App)

Scenario: **offline semantic search** over a curated FAQ knowledge base (DB text), with **optional** context-based answer generation when an LLM provider is added.

It also includes a local RAG quality workbench: seed the baseline FAQ corpus, run golden questions,
inspect retrieved evidence, and fail the gate when expected FAQ articles are not retrieved. Run the
quality workbench with the app's normal local profile so it uses the app's Lucene + `simple`
embedding setup; the shared `smoke` profile is for no-key startup checks.

## What this app proves

- Config-driven AI setup via `ai-entity-config.yml` (no AI annotations required)
- Local-first stack by default: **H2 + deterministic local embeddings + Lucene vector DB**
- Simple, realistic demo flow: seed → reindex → semantic search → optional “ask”
- Release-style quality gate: golden questions → AI Fabric search → retrieved evidence → pass/fail
- Optional Spring AI-backed RAG evaluation when `ai.infrastructure.rag.evaluation.enabled=true` and a real `ChatClient.Builder` is configured

## Run

1) Build framework artifacts:

`cd ai-infrastructure-module && mvn -DskipTests install`

2) Run the app:

`cd examples/real-apps/smart-faq-assistant && mvn -DskipTests package && java -jar target/*.jar`

App port: `8094`

## Endpoints

- `POST /api/demo/seed` (creates sample FAQ articles + indexes them)
- `POST /api/demo/indexing/reindex/articles` (re-index all existing articles)
- `POST /api/demo/quality/seed-and-run` (seed baseline corpus, run golden RAG retrieval gate)
- `GET /api/faq/search?q=...&limit=...` (semantic search)
- `POST /api/faq/ask` (search-only by default; optional contextual generation)
- `GET /api/faq/quality/golden` (list golden questions)
- `POST /api/faq/quality/golden/run` (run the quality gate against current indexed content)

Use `requests/demo.http` to run the full scenario.

## Optional: enable answer generation

By default the app runs without any LLM provider dependency (so it’s always runnable).

If you add an LLM provider module + set its keys, you can enable contextual answer generation:

- `AI_FAQ_ENABLE_GENERATION=true`
- `AI_FAQ_ENABLE_RAG=true`

## Optional: Spring AI RAG evaluation

The golden gate runs locally by default and checks whether expected FAQ articles are retrieved.
If a Spring AI `ChatClient.Builder` is configured, enable evaluator-backed checks with:

- `ai.infrastructure.rag.evaluation.enabled=true`

Then call `POST /api/faq/quality/golden/run` with:

```json
{
  "threshold": 0.0,
  "springAiEvaluation": true
}
```
