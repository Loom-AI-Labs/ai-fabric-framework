# AI Fabric Module Decision Tree

## Start

Every Spring Boot integration normally starts with:

- `ai-fabric-starter`
- `@EnableAIInfrastructure`

## Need LLM Generation?

Add:

- `ai-fabric-provider-spring-ai`

Configure:

- `ai.providers.llm-provider`
- provider API key/model

## Need Embeddings?

Choose one:

- remote embeddings: `ai-fabric-provider-spring-ai`
- local embeddings: `ai-fabric-onnx-starter`
- tests only: smoke embedding provider

## Need Vector Storage?

Choose one:

- local durable: `ai-fabric-vector-lucene`
- no-key smoke: `ai-fabric-vector-memory`
- managed: `ai-fabric-vector-qdrant`, `ai-fabric-vector-pinecone`, `ai-fabric-vector-weaviate`, or `ai-fabric-vector-milvus`

## Need RAG?

Add:

- `ai-fabric-rag`
- LLM provider
- embedding provider
- vector provider

## Need Actions?

Add:

- local annotations: `ai-fabric-starter` is enough for local `@AIAction` discovery
- file/catalog connector actions: `ai-fabric-actions-connector`
- DB connector registry: `ai-fabric-actions-registry`
- confirmations/follow-up: `ai-fabric-chat-session`

## Need Conversation Memory?

Add:

- `ai-fabric-chat-session`
- database/JPA support in the app

## Need Behavior Analytics?

Add:

- `ai-fabric-behavior`
- LLM provider

Feed raw app events. Let the LLM infer the behavioral meaning.

## Need Tenant Safety?

Add:

- access policy implementation
- metadata-scoped indexing
- provider capability checks

Do not trust client-supplied tenant/account IDs.

