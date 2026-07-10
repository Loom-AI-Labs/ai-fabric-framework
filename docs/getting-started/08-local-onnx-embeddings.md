# Local ONNX Embeddings

Use `ai-fabric-onnx-starter` when embeddings should run locally without remote API calls.

## Dependency

```xml
<dependency>
  <groupId>io.github.loom-ai-labs</groupId>
  <artifactId>ai-fabric-onnx-starter</artifactId>
</dependency>
```

## Configuration

```yaml
ai:
  providers:
    embedding-provider: onnx
    onnx:
      model-path: ${AI_FABRIC_ONNX_MODEL_PATH:./models/embeddings/all-MiniLM-L6-v2.onnx}
      tokenizer-path: ${AI_FABRIC_ONNX_TOKENIZER_PATH:./models/embeddings/tokenizer.json}
```

## Download Model Assets

AI Fabric does not ship third-party model binaries.

```bash
cd ai-infrastructure-module
./scripts/download-onnx-model.sh
```

Then point `AI_FABRIC_ONNX_MODEL_PATH` and `AI_FABRIC_ONNX_TOKENIZER_PATH` to the downloaded files.

## When To Use ONNX

Use ONNX embeddings when:

- local development should avoid embedding API calls
- data sensitivity discourages remote embedding
- cost control matters
- latency is acceptable for local inference

Use a remote embedding provider when:

- you need higher-quality embeddings from a hosted model
- you want operationally managed model serving
- your production environment already standardizes on provider APIs

## Smoke Alternative

For tests that should not depend on model files, use the `smoke` provider and memory vector store:

```yaml
ai:
  providers:
    embedding-provider: smoke
  vector-db:
    type: memory
```

## What To Test

- model files exist before startup
- embedding dimensions match the configured vector store/index
- Lucene index path changes when embedding dimensions change
- no remote embedding calls happen in ONNX profile

