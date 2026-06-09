# Smart FAQ Assistant (Real_App)

Scenario: **offline semantic search** over a curated FAQ knowledge base (DB text), with **optional** context-based answer generation when an LLM provider is added.

## What this app proves

- Config-driven AI setup via `ai-entity-config.yml` (no AI annotations required)
- Local-first stack by default: **H2 + deterministic local embeddings + Lucene vector DB**
- Simple, realistic demo flow: seed → reindex → semantic search → optional “ask”

## Run

1) Build framework artifacts:

`cd ai-infrastructure-module && mvn -DskipTests install`

2) Run the app:

`cd examples/real-apps/smart-faq-assistant && mvn -DskipTests package && java -jar target/*.jar`

App port: `8094`

## Endpoints

- `POST /api/demo/seed` (creates sample FAQ articles + indexes them)
- `POST /api/demo/indexing/reindex/articles` (re-index all existing articles)
- `GET /api/faq/search?q=...&limit=...` (semantic search)
- `POST /api/faq/ask` (search-only by default; optional contextual generation)

Use `requests/demo.http` to run the full scenario.

## Optional: enable answer generation

By default the app runs without any LLM provider dependency (so it’s always runnable).

If you add an LLM provider module + set its keys, you can enable contextual answer generation:

- `AI_FAQ_ENABLE_GENERATION=true`
- `AI_FAQ_ENABLE_RAG=true`
