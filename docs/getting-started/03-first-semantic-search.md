# First Semantic Search

Semantic search is the smallest useful AI Fabric integration: embed app data, store vectors, and
retrieve matching records by meaning.

## Dependencies

```xml
<dependency>
  <groupId>io.github.loom-ai-labs</groupId>
  <artifactId>ai-fabric-starter</artifactId>
</dependency>
<dependency>
  <groupId>io.github.loom-ai-labs</groupId>
  <artifactId>ai-fabric-provider-spring-ai</artifactId>
</dependency>
<dependency>
  <groupId>io.github.loom-ai-labs</groupId>
  <artifactId>ai-fabric-vector-lucene</artifactId>
</dependency>
```

For smoke tests, add:

```xml
<dependency>
  <groupId>io.github.loom-ai-labs</groupId>
  <artifactId>ai-fabric-vector-memory</artifactId>
  <scope>test</scope>
</dependency>
```

## Define Searchable Data

```java
import ai.fabric.annotation.AICapable;
import ai.fabric.annotation.AIContext;
import ai.fabric.annotation.AIIdentity;
import ai.fabric.annotation.AISearchable;
import ai.fabric.indexing.api.AIContextDataType;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;

@Entity
@AICapable(entityType = "faq-article")
public class FaqArticle {
    @Id
    @AIIdentity
    @AIContext(
        key = "entityId",
        dataType = AIContextDataType.ID,
        priority = 100,
        required = true
    )
    private String id;

    @AISearchable(priority = 100, required = true)
    private String title;

    @AISearchable(maxLength = 8000, priority = 80, required = true)
    private String answer;

    @AIContext(priority = 70)
    private String category;
}
```

Annotation-backed entities need no YAML entry. YAML can apply a typed operational override:

```yaml
ai-entities:
  faq-article:
    indexing:
      enabled: true
      max-characters: 8000
    analysis:
      enabled: false
```

Use `@AIProcess` on the public transactional service boundary:

```java
import ai.fabric.annotation.AIProcess;
import ai.fabric.indexing.api.AIProcessOperation;
import org.springframework.transaction.annotation.Transactional;

@Transactional
@AIProcess(operation = AIProcessOperation.CREATE)
public FaqArticle create(FaqArticle article) {
    return repository.saveAndFlush(article);
}
```

The source record and its approved projected queue row commit together. With `SYNC`, provider work is
attempted after source commit; with `ASYNC`, a worker processes the durable row. A rollback produces
neither provider mutation nor committed indexing work.

## Index Programmatically

For lifecycle paths that cannot use Spring AOP, use the public gateway:

```java
import ai.fabric.indexing.api.AIEntityIndexingGateway;
import ai.fabric.indexing.api.AIProcessOperation;
import ai.fabric.indexing.api.IndexingOutcome;
import ai.fabric.indexing.api.IndexingStrategy;

IndexingOutcome outcome = aiEntityIndexingGateway.upsert(
    article,
    AIProcessOperation.CREATE,
    IndexingStrategy.SYNC
);
```

Deletion uses the same stable identity:

```java
IndexingOutcome outcome = aiEntityIndexingGateway.delete(
    FaqArticle.class,
    articleId,
    IndexingStrategy.SYNC
);
```

Raw embedding and vector APIs remain available for provider-level work, but they are not the entity
lifecycle contract.

## What To Test

- Index one record and search with different wording.
- Update the record and verify the old content is not returned.
- Delete the record and verify it disappears.
- Verify metadata is present for UI/evidence display.
- Roll back a source transaction and verify no queue row or vector is created.
- Force a provider failure and verify retry/dead-letter state is visible.
- Verify tenant/user filters before exposing search to users.

## Real App Reference

Copy from:

- `examples/real-apps/smart-faq-assistant`
- `examples/real-apps/chat-capabilities-demo`
- `examples/real-apps/cloud-qdrant-openai-vector-search`
