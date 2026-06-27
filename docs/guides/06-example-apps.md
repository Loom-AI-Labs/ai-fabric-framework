# 6. Example Applications

The framework ships a suite of runnable example apps under `examples/real-apps/`. Each is a small,
standalone Spring Boot application that demonstrates one slice of AI Fabric against realistic domain
code. They are the fastest way to see how the framework is used in practice.

## Run any example offline (no keys)

Every example boots fully offline with the **`smoke`** profile — no API keys, no external services.
The profile is provided by the shared `smoke-support` module (a deterministic local LLM provider,
in-process embeddings, an in-memory vector store, and H2).

```bash
# build the framework and the suite once
mvn -f ai-infrastructure-module/pom.xml -q install
mvn -f examples/real-apps/pom.xml -q install

# run an app under the smoke profile
mvn -pl <app> -f examples/real-apps/pom.xml spring-boot:run -Dspring-boot.run.profiles=smoke
# or
java -jar examples/real-apps/<app>/target/<app>.jar --spring.profiles.active=smoke
```

To run an app for real (with live providers/services), drop the profile and supply the relevant
configuration/keys instead.

## The examples

| App | Demonstrates | Key modules | Real services to run "for real" |
|-----|--------------|-------------|----------------------------------|
| **smart-faq-assistant** | Semantic search over FAQs; optional RAG | rag, indexing, onnx, vector-lucene | None (local by default) |
| **relationship-query-crm-insights** | Natural language → JPQL over CRM entities | relationship-query | An LLM provider key |
| **cloud-qdrant-openai-vector-search** | Production-like semantic search | vector-qdrant, provider-openai, rag | Postgres + Qdrant + OpenAI |
| **privacy-first-customer-facing-support** | PII detection & redaction | pii, chat-session | None |
| **chat-capabilities-demo** | Conversation context + turn recording | chat-session, provider-openai | An LLM provider key |
| **it-support-action-bot** | Action/tool execution (no vector DB) | actions-registry, provider | An LLM provider key |
| **migration-enabled-product-catalog** | Bulk backfill indexing | migration, indexing, vector-lucene | None (H2 + local embeddings) |
| **behavior-churn-signals** | Churn + sentiment insights | behavior | None (offline local LLM) |
| **ecommerce-store** | Domain API used as a base for AI demos | (domain only) | None |
| **sub-management-hub** / **-simple** | AI-powered subscription management | rag, provider-spring-ai, vector-lucene | An OpenAI key for live LLM behavior |

> `smoke-support` is a shared helper module, not a runnable app.

## How to read an example

Each example follows the same shape, which is also the shape of your own app:

1. **`pom.xml`** — imports `ai-fabric-bom` and declares the modules the app uses.
2. **A `@SpringBootApplication` + `@EnableAIInfrastructure`** main class.
3. **`src/main/resources/application.yml`** — selects providers / vector store / features under
   `ai.*`, plus an `application-smoke.yml` (in the suite) for offline runs.
4. **Domain code + controllers** that inject AI Fabric services (e.g. a `VectorDatabaseService`, a
   relationship-query service, or the chat-session API) and expose them over HTTP.

Start with **smart-faq-assistant** (semantic search) and **relationship-query-crm-insights** (NL →
query) — they cover the two most common patterns and both run offline under `smoke`.

## Next

→ [Quickstart: Build Your First App](07-quickstart.md)
