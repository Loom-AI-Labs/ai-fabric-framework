# Choose Your Path

Start with the smallest capability that proves value, then add modules as the app needs them.

## Path 1: Semantic Search

Use when users need to find app data by meaning.

Add:

- `ai-fabric-starter`
- one embedding provider: `ai-fabric-provider-spring-ai` or `ai-fabric-onnx-starter`
- one vector provider: `ai-fabric-vector-lucene`, `ai-fabric-vector-memory`, or a managed provider
- optional: `ai-fabric-indexing`

Read next:

- [First Semantic Search](03-first-semantic-search.md)
- [Vector Storage Lucene](09-vector-storage-lucene.md)

## Path 2: RAG Chat

Use when the app must answer from retrieved evidence.

Add:

- everything in semantic search
- `ai-fabric-rag`
- an LLM provider, usually `ai-fabric-provider-spring-ai`
- optional: `ai-fabric-curated-default`, `ai-fabric-curated-commerce`, or `ai-fabric-curated-support`

Read next:

- [First RAG Chat](04-first-rag-chat.md)
- [Real Provider OpenAI](07-real-provider-openai.md)

## Path 3: Governed Actions

Use when the assistant can perform app work: update payment method, create ticket, add to cart,
cancel subscription, request refund, or run a read action.

Add:

- `ai-fabric-starter`
- `ai-fabric-actions-connector` for file/catalog backed connector actions
- `ai-fabric-actions-registry` for database-backed connector actions
- `ai-fabric-chat-session` for confirmations and follow-up turns

Read next:

- [First Governed Action](05-first-governed-action.md)
- [Chat Session Memory](06-chat-session-memory.md)

## Path 4: Chat Memory

Use when the user will say "yes", "add it", "compare those", or "continue" in a later turn.

Add:

- `ai-fabric-chat-session`
- a persistent database for `chat_sessions` and `chat_turns`

Read next:

- [Chat Session Memory](06-chat-session-memory.md)

## Path 5: Behavior Intelligence

Use when raw app events should become user behavior insights, risk signals, agentic UI
recommendations, or next-best action families.

Add:

- `ai-fabric-behavior`
- real LLM provider for analysis
- application events stored in your app database

Copy from:

- `examples/real-apps/behavior-churn-signals`

## Path 6: Tenant-Safe Retrieval

Use when multiple tenants share infrastructure and every retrieval must be scoped.

Add:

- vector provider with metadata filtering support for your use case
- access policy implementation
- metadata such as `tenantId`, `visibility`, and `ownerId`

Copy from:

- `examples/real-apps/tenant-knowledge-portal`

## Recommended First App

For the fastest full-stack proof, start with:

- semantic search
- RAG
- one read action
- one confirmation-required side-effect action
- chat session memory
- smoke profile tests

The closest real app template is `examples/real-apps/chat-capabilities-demo`.

