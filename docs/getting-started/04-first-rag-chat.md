# First RAG Chat

AI Fabric RAG retrieves evidence and builds context. It does not need to replace your app's domain
logic. Your app decides what data is indexed, AI Fabric retrieves relevant records, and the LLM
generates an answer from that evidence.

## Dependencies

```xml
<dependency>
  <groupId>io.github.loom-ai-labs</groupId>
  <artifactId>ai-fabric-rag</artifactId>
</dependency>
<dependency>
  <groupId>io.github.loom-ai-labs</groupId>
  <artifactId>ai-fabric-provider-spring-ai</artifactId>
</dependency>
```

You also need an embedding provider and vector provider from the semantic search guide.

## Configure RAG

```yaml
ai:
  service:
    features:
      enable-generation: true
      enable-embeddings: true
      enable-search: true
      enable-rag: true
  rag:
    default-limit: 8
    default-threshold: 0.55
```

## Retrieve Evidence

`RAGService` is retrieval-focused:

```java
RAGResponse response = ragService.performRag(
    RAGRequest.builder()
        .query("Can I return an opened gaming laptop?")
        .entityType("policy")
        .limit(5)
        .threshold(0.55)
        .includeMetadata(true)
        .build());
```

The response contains retrieved documents, scores, metadata, and context. Use those fields to prove
that an answer is evidence-backed.

## Generate The Answer

For a simple app, pass the retrieved context to `AICoreService`:

```java
String answer = aiCoreService.generateText("""
Answer the user's question using only the evidence below.

Question:
%s

Evidence:
%s
""".formatted(userQuestion, response.getContext()));
```

For chat apps, prefer the framework orchestration/web pipeline when available so the same request
can use mode selection, actions, chat memory, and confirmation handling.

## Evidence Rules

- Show retrieved documents in logs, tests, or UI evidence panels.
- If no documents are retrieved, say the answer is not evidence-backed.
- Do not invent policies or product facts.
- Keep policies as user-friendly descriptions. Use prompt overlays for control language when needed.

## What To Test

- Empty vector store returns no-evidence behavior.
- Product-only data cannot answer review/policy questions.
- Adding policies changes the answer source.
- Adding reviews changes comparison quality.
- Metadata and scores are visible to diagnostics or UI.

## Real App Reference

Copy from:

- `examples/real-apps/chat-capabilities-demo`
- `examples/real-apps/tenant-knowledge-portal`

