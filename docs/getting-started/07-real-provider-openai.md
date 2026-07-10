# Real Provider: OpenAI Through Spring AI

AI Fabric uses `ai-fabric-provider-spring-ai` for LLM and embedding calls where Spring AI can
express the needed behavior. AI Fabric keeps its own policy, orchestration, action, RAG, and
diagnostic layers above the provider.

## Dependency

```xml
<dependency>
  <groupId>io.github.loom-ai-labs</groupId>
  <artifactId>ai-fabric-provider-spring-ai</artifactId>
</dependency>
```

## Configuration

```yaml
ai:
  providers:
    llm-provider: openai
    embedding-provider: openai
    openai:
      enabled: ${OPENAI_ENABLED:false}
      api-key: ${OPENAI_API_KEY:}
      base-url: ${OPENAI_BASE_URL:https://api.openai.com/v1}
      model: ${OPENAI_MODEL:gpt-4o-mini}
      embedding-model: ${OPENAI_EMBEDDING_MODEL:text-embedding-3-small}
      embedding-dimensions: ${OPENAI_EMBEDDING_DIMENSIONS:512}
      timeout: 45
```

## Why Use Spring AI Here

For LLM and embeddings, Spring AI gives AI Fabric a stable model abstraction, standard options, and
provider support. AI Fabric adds:

- mode selection
- structured output parsing and repair
- RAG evidence handling
- action extraction and confirmation
- chat session memory
- transient input policy
- usage/diagnostic evidence

## Escape Hatches

Keep native-provider escape hatches only for:

- provider not supported by Spring AI
- feature not exposed by Spring AI yet
- provider-specific beta API
- per-request endpoint override that cannot be modeled cleanly with an AI Fabric model resolver/cache
- transient file URL behavior that needs parity testing with AI Fabric policy

## What To Test

- generation request succeeds with `OPENAI_ENABLED=true`
- embedding request returns expected dimensions
- model name and purpose are visible in diagnostics
- invalid key fails clearly
- no-key smoke profile still works

## Real App Reference

Copy from:

- `examples/real-apps/chat-capabilities-demo`
- `examples/real-apps/ai-fabric-account-resolver`
- `examples/real-apps/behavior-churn-signals`
- `examples/real-apps/tenant-knowledge-portal`

