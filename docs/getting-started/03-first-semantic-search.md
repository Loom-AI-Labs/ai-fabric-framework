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
import ai.fabric.annotation.AISearchable;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;

@Entity
@AICapable(entityType = "faq-article")
public class FaqArticle {
    @Id
    private String id;

    @AISearchable(weight = 2.0)
    private String title;

    @AISearchable(maxLength = 8000)
    private String answer;

    @AIContext
    private String category;
}
```

YAML can also define entity behavior:

```yaml
ai-entities:
  faq-article:
    features: ["embedding", "search"]
    auto-process: true
    enable-search: true
    auto-embedding: true
    indexable: true
```

## Store And Search Manually

Manual indexing is useful for understanding the contract and for non-JPA data.

```java
List<Double> embedding = embeddingService
    .generateEmbeddings(List.of(content), "faq-article")
    .get(0)
    .getEmbedding();

vectorDatabaseService.storeVector(
    "faq-article",
    articleId,
    content,
    embedding,
    Map.of("category", category));
```

```java
List<Double> queryVector = embeddingService
    .generateEmbeddings(List.of(query), "faq-article")
    .get(0)
    .getEmbedding();

AISearchResponse response = vectorDatabaseService.search(
    queryVector,
    AISearchRequest.builder()
        .query(query)
        .entityType("faq-article")
        .limit(5)
        .threshold(0.5)
        .build());
```

## What To Test

- Index one record and search with different wording.
- Update the record and verify the old content is not returned.
- Delete the record and verify it disappears.
- Verify metadata is present for UI/evidence display.
- Verify tenant/user filters before exposing search to users.

## Real App Reference

Copy from:

- `examples/real-apps/smart-faq-assistant`
- `examples/real-apps/chat-capabilities-demo`
- `examples/real-apps/cloud-qdrant-openai-vector-search`

