# Vector Storage: Lucene

`ai-fabric-vector-lucene` is the recommended local durable vector store. It is useful for demos,
single-node apps, local development, and CI-style smoke verification.

## Dependency

```xml
<dependency>
  <groupId>io.github.loom-ai-labs</groupId>
  <artifactId>ai-fabric-vector-lucene</artifactId>
</dependency>
```

## Configuration

```yaml
ai:
  vector-db:
    type: lucene
    lucene:
      index-path: ./data/lucene-vector-index-${OPENAI_EMBEDDING_DIMENSIONS:512}
```

Include embedding dimensions in the path when switching embedding models. Lucene indexes cannot
safely mix vectors with different dimensions.

## What Lucene Supports

Lucene provides:

- local durable vector storage
- vector similarity search
- metadata attached to vector records
- lifecycle operations through AI Fabric's `VectorDatabaseService`
- local development without a managed vector service

For tenant-safe production retrieval, verify the exact metadata filtering semantics needed by your
app. AI Fabric exposes provider capabilities so admin/readiness checks can fail closed when a
provider cannot preserve required filtering.

## Common Operations

```java
vectorDatabaseService.storeVector(entityType, entityId, content, embedding, metadata);
vectorDatabaseService.search(queryVector, searchRequest);
vectorDatabaseService.getVectorByEntity(entityType, entityId);
vectorDatabaseService.updateVector(vectorId, entityType, entityId, content, embedding, metadata);
vectorDatabaseService.removeVector(entityType, entityId);
```

## What To Test

- restart app and verify vectors are still present
- search by entity type
- remove by entity and verify no stale result
- metadata attached to result is complete
- tenant/user filter behavior before multi-tenant exposure

## Real App Reference

Copy from:

- `examples/real-apps/chat-capabilities-demo`
- `examples/real-apps/ai-fabric-account-resolver`
- `examples/real-apps/tenant-knowledge-portal`

