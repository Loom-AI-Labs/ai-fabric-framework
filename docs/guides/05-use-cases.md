# 5. Use Cases

AI Fabric is a toolkit for common AI application patterns. This page maps real problems to the
modules and configuration that solve them, with a matching example app you can run (see
[Example Applications](06-example-apps.md)).

## Semantic search over your content

**Problem:** users should find answers by meaning, not exact keywords.

**Solution:** embed your content and store it in a vector store; embed queries and retrieve the
closest matches.

- Modules: `ai-fabric-starter`, `ai-fabric-onnx-starter` (local embeddings), `ai-fabric-vector-lucene`,
  `ai-fabric-rag`, `ai-fabric-indexing`.
- Example: **smart-faq-assistant**.

## Retrieval-augmented generation (RAG)

**Problem:** generate answers grounded in your own data, not just the model's training.

**Solution:** retrieve relevant content from the vector store and pass it as context to the LLM.

- Modules: search stack above + an LLM provider (`ai-fabric-provider-spring-ai`) + `ai-fabric-rag`.
- Example: **smart-faq-assistant** (optional RAG), **cloud-qdrant-openai-vector-search**.

## Natural language → database queries

**Problem:** let users ask questions in plain English and query your relational data.

**Solution:** the relationship-query module translates natural language into JPA/JPQL over your
entities, executes it, and returns results.

- Modules: `ai-fabric-relationship-query` + an LLM provider.
- Example: **relationship-query-crm-insights**.

## Production-grade semantic search with a cloud vector DB

**Problem:** scale search beyond a single node with a managed vector database and cloud embeddings.

**Solution:** swap the vector store and embedding provider via configuration — no code change.

- Modules: `ai-fabric-vector-qdrant`, `ai-fabric-provider-spring-ai` (embeddings), `ai-fabric-rag`.
- Example: **cloud-qdrant-openai-vector-search** (Postgres + Qdrant + OpenAI).

## Privacy-first, customer-facing AI

**Problem:** customer interactions contain PII that must be detected and redacted.

**Solution:** the PII module detects and redacts sensitive data in your flows.

- Modules: `ai-fabric-pii` (+ `ai-fabric-chat-session` for conversations).
- Example: **privacy-first-customer-facing-support**.

## Conversational assistants with memory

**Problem:** chat experiences need conversation context across turns.

**Solution:** the chat-session module records turns and maintains conversation context.

- Modules: `ai-fabric-chat-session` + an LLM provider.
- Example: **chat-capabilities-demo**.

## Action / tool execution ("do something", not just answer)

**Problem:** the assistant should take actions (create a ticket, update a record), not only reply.

**Solution:** register actions in the actions registry; the framework manages pending/draft actions
and execution.

- Modules: `ai-fabric-actions-registry` (+ `-liquibase`, `-connector`).
- Example: **it-support-action-bot**.

## Indexing & backfilling existing data

**Problem:** you have a large existing dataset to make searchable, and ongoing changes to keep in
sync.

**Solution:** bulk-backfill with the migration module; keep current with data-sync.

- Modules: `ai-fabric-migration`, `ai-fabric-indexing`, `ai-fabric-data-sync`.
- Example: **migration-enabled-product-catalog**.

## Behavioral insights

**Problem:** derive signals like churn risk or sentiment from activity.

**Solution:** the behavior module produces behavioral insights.

- Modules: `ai-fabric-behavior`.
- Example: **behavior-churn-signals**.

## Switching backends without code changes

A recurring theme: **provider and store choices are configuration, not code.** Start local (ONNX +
Lucene), move to cloud (OpenAI + Qdrant) by changing dependencies and `ai.*` properties. The same
application code keeps working.

## Next

→ [Example Applications](06-example-apps.md)
