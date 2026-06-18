# 7. Quickstart: Build Your First App

In this guide you'll build a tiny semantic-search service from scratch: index a few documents, then
search them by meaning. It runs **offline** with the `smoke` profile (no keys), and the same code
works against real providers when you're ready.

## 1. Create a Spring Boot project

A standard Spring Boot 3.2.x app on Java 21. Add the AI Fabric BOM and the modules you need.

```xml
<project>
  <!-- ... your Spring Boot app coordinates ... -->

  <dependencyManagement>
    <dependencies>
      <dependency>
        <groupId>io.github.loom-ai-labs</groupId>
        <artifactId>ai-fabric-bom</artifactId>
        <version>0.2.1</version>
        <type>pom</type>
        <scope>import</scope>
      </dependency>
    </dependencies>
  </dependencyManagement>

  <dependencies>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-web</artifactId>
    </dependency>

    <dependency>
      <groupId>io.github.loom-ai-labs</groupId>
      <artifactId>ai-fabric-starter</artifactId>
    </dependency>
    <dependency>
      <groupId>io.github.loom-ai-labs</groupId>
      <artifactId>ai-fabric-onnx-starter</artifactId>
    </dependency>
    <dependency>
      <groupId>io.github.loom-ai-labs</groupId>
      <artifactId>ai-fabric-vector-lucene</artifactId>
    </dependency>

    <!-- Offline vector store for the 'smoke' profile (no keys/models needed). Optional. -->
    <dependency>
      <groupId>io.github.loom-ai-labs</groupId>
      <artifactId>ai-fabric-vector-memory</artifactId>
    </dependency>
  </dependencies>
</project>
```

## 2. Enable the framework

```java
package com.example.quickstart;

import ai.fabric.annotation.EnableAIInfrastructure;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@EnableAIInfrastructure
public class QuickstartApplication {
    public static void main(String[] args) {
        SpringApplication.run(QuickstartApplication.class, args);
    }
}
```

## 3. Configure

`src/main/resources/application.yml` — real (local ONNX + Lucene) config:

```yaml
ai:
  providers:
    embedding-provider: onnx
    onnx:
      model-path: ${AI_FABRIC_ONNX_MODEL_PATH:./models/embeddings/all-MiniLM-L6-v2.onnx}
      tokenizer-path: ${AI_FABRIC_ONNX_TOKENIZER_PATH:./models/embeddings/tokenizer.json}
  vector-db:
    type: lucene
```

`src/main/resources/application-smoke.yml` — zero-setup offline config:

```yaml
ai:
  providers:
    embedding-provider: smoke
  vector-db:
    type: memory
```

## 4. Index and search

Inject the framework's `AIEmbeddingService` (turns text into vectors) and `VectorDatabaseService`
(stores and searches them).

```java
package com.example.quickstart;

import ai.fabric.core.AIEmbeddingService;
import ai.fabric.dto.AIEmbeddingResponse;
import ai.fabric.dto.AISearchRequest;
import ai.fabric.dto.AISearchResponse;
import ai.fabric.rag.VectorDatabaseService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/docs")
public class DocsController {

    private static final String ENTITY_TYPE = "doc";

    private final AIEmbeddingService embeddings;
    private final VectorDatabaseService vectors;

    public DocsController(AIEmbeddingService embeddings, VectorDatabaseService vectors) {
        this.embeddings = embeddings;
        this.vectors = vectors;
    }

    /** Index a document: embed its text, then store the vector. */
    @PostMapping
    public String index(@RequestBody String content) {
        List<Double> embedding = embeddings
            .generateEmbeddings(List.of(content), ENTITY_TYPE)
            .get(0)
            .getEmbedding();

        return vectors.storeVector(
            ENTITY_TYPE,
            UUID.randomUUID().toString(),
            content,
            embedding,
            Map.of("source", "quickstart"));
    }

    /** Search by meaning: embed the query, then retrieve the closest documents. */
    @GetMapping("/search")
    public AISearchResponse search(@RequestParam String q) {
        List<Double> queryVector = embeddings
            .generateEmbeddings(List.of(q), ENTITY_TYPE)
            .get(0)
            .getEmbedding();

        AISearchRequest request = AISearchRequest.builder()
            .query(q)
            .entityType(ENTITY_TYPE)
            .limit(5)
            .threshold(0.5)
            .build();

        return vectors.search(queryVector, request);
    }
}
```

## 5. Run it (offline)

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=smoke
```

Index a couple of documents and search:

```bash
curl -X POST localhost:8080/docs -H 'Content-Type: text/plain' \
     -d 'Refunds are processed within 5 business days.'
curl -X POST localhost:8080/docs -H 'Content-Type: text/plain' \
     -d 'You can reset your password from the account settings page.'

curl 'localhost:8080/docs/search?q=how%20long%20until%20I%20get%20my%20money%20back'
```

The refund document ranks first even though the query shares no keywords with it — that's semantic
search.

## 6. Go to production

Switch from the offline local providers to real backends by **changing configuration, not code**:

- Add a real embedding/LLM provider (e.g. `ai-fabric-provider-spring-ai`) and set
  `ai.providers.embedding-provider` / `ai.providers.llm-provider` + the API key.
- Point `ai.vector-db.type` at a managed store (e.g. `qdrant`) and add `ai-fabric-vector-qdrant`.
- Layer on RAG (`ai-fabric-rag`), NL→query (`ai-fabric-relationship-query`), PII
  (`ai-fabric-pii`), actions (`ai-fabric-actions-registry`), and more.

## Where to go next

- [Modules Reference](03-modules.md) — the full catalog.
- [Configuration Reference](04-configuration.md) — every `ai.*` property.
- [Example Applications](06-example-apps.md) — complete, runnable apps to copy from.
