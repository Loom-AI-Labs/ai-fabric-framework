# 4. Configuration Reference

AI Fabric is configured with standard Spring Boot properties under the `ai.*` namespace. This page
lists the most important keys. Property prefixes are **not** Java packages — they are stable
configuration keys.

> Tip: most beans are `@ConditionalOnMissingBean`, so you can override any of these by supplying your
> own bean. Modules only activate when both present on the classpath and selected here.

## Master switch

| Property | Default | Description |
|----------|---------|-------------|
| `ai.enabled` | `true` | Master toggle for the framework's auto-configuration. |

## Providers (`ai.providers.*`)

### Selection

| Property | Example | Description |
|----------|---------|-------------|
| `ai.providers.llm-provider` | `openai` | Name of the active `AIProvider` used for generation. Matches a provider's `getProviderName()`. |
| `ai.providers.embedding-provider` | `onnx` | Name of the active `EmbeddingProvider`. Defaults to `onnx` when unset. |
| `ai.providers.enable-fallback` | `true` | Allow falling back to another available provider. |

### Per-provider settings

Each LLM provider has its own block (`openai`, `azure`, `anthropic`, `cohere`, `gemini`). A provider
is gated by `enabled` (OpenAI defaults to enabled; the others must be enabled explicitly):

```yaml
ai:
  providers:
    llm-provider: openai
    openai:
      enabled: true
      api-key: ${OPENAI_API_KEY}
      base-url: https://api.openai.com/v1
      model: gpt-4o-mini
    anthropic:
      enabled: false
      api-key: ${ANTHROPIC_API_KEY}
      model: claude-sonnet-4-6
    cohere:
      enabled: false
      api-key: ${COHERE_API_KEY}
```

| Property (per provider `<p>`) | Description |
|-------------------------------|-------------|
| `ai.providers.<p>.enabled` | Register this provider's beans. |
| `ai.providers.<p>.api-key` | Credential (inject via env var). |
| `ai.providers.<p>.base-url` | API base URL (override for proxies/compatible endpoints). |
| `ai.providers.<p>.model` | Default model id. |

### Local ONNX embeddings (`ai.providers.onnx.*`)

```yaml
ai:
  providers:
    embedding-provider: onnx
    onnx:
      model-path: ${AI_FABRIC_ONNX_MODEL_PATH:./models/embeddings/all-MiniLM-L6-v2.onnx}
      tokenizer-path: ${AI_FABRIC_ONNX_TOKENIZER_PATH:./models/embeddings/tokenizer.json}
      max-sequence-length: 512
      use-gpu: false
```

## Vector store (`ai.vector-db.*`)

```yaml
ai:
  vector-db:
    type: lucene            # lucene | memory | qdrant | pinecone | weaviate | milvus
    lucene:
      index-path: ./data/lucene-vector-index
      similarity-threshold: 0.7
```

| Property | Default | Description |
|----------|---------|-------------|
| `ai.vector-db.type` | `lucene` | Which `VectorDatabaseService` to activate. |
| `ai.vector-db.lucene.index-path` | `./data/lucene-vector-index` | Lucene index directory. |
| `ai.vector-db.lucene.similarity-threshold` | `0.7` | Minimum similarity for matches. |

Cloud stores use their own connection blocks, e.g. Qdrant:

```yaml
ai:
  vector-db:
    type: qdrant
  providers:
    qdrant:
      host: ${AI_PROVIDERS_QDRANT_HOST:localhost}
      grpc-port: ${AI_PROVIDERS_QDRANT_GRPC_PORT:6334}
      api-key: ${AI_PROVIDERS_QDRANT_API_KEY:}
```

## Feature toggles (`ai.service.features.*`)

| Property | Default | Description |
|----------|---------|-------------|
| `ai.service.features.enable-embeddings` | `true` | Enable the embedding service. |
| `ai.service.features.enable-generation` | varies | Enable generation flows. |
| `ai.service.features.enable-search` | varies | Enable search flows. |

Turn off what an app doesn't need (e.g. a pure NL→query app may disable embeddings and the vector
store).

## The `smoke` profile (offline, no keys)

The example apps ship a `smoke` profile that selects deterministic local providers end-to-end:

```yaml
ai:
  providers:
    llm-provider: smoke         # deterministic local provider
    embedding-provider: smoke   # deterministic, in-process (no ONNX file)
  vector-db:
    type: memory                # in-process vector store
```

Activate with `--spring.profiles.active=smoke`. See [Example Applications](06-example-apps.md).

## Next

→ [Use Cases](05-use-cases.md)
