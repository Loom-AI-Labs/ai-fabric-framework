# IT Support Action Bot (Provider-Only)

**Scenario:** Provider-only orchestration + actions (no vector DB, no indexing, no RAG). This validates that AI Fabric can power action bots with only an LLM provider configured.

## Setup

- Spring Boot `3.2.x`, Java `21`
- Database: H2 file (`./data/it-support.db`)
- AI: **LLM only** (OpenAI provider module)
- Port: `8082`

## Build + Run

1) Install the framework artifacts:

`cd ../../ai-infrastructure-module && mvn -DskipTests install`

2) Run the app:

`cd examples/real-apps/it-support-action-bot && mvn -DskipTests clean package && java -jar target/*.jar`

## Enable the LLM provider

By default, the app boots without requiring an API key. To run the bot:

- `export OPENAI_ENABLED=true`
- `export OPENAI_API_KEY=...`

## Validate (Scenarios)

Use `examples/real-apps/it-support-action-bot/requests/demo.http`:

- Seed demo tickets and agents
- Ask the bot to create/assign/close/escalate tickets
- Inspect tickets via REST endpoints
