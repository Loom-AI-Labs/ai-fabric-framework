# Installation

AI Fabric is distributed on Maven Central under `io.github.loom-ai-labs`.

## Requirements

- Java `21`
- Maven `3.9+`
- Spring Boot `4.1.x`

## Import The BOM

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>io.github.loom-ai-labs</groupId>
      <artifactId>ai-fabric-bom</artifactId>
      <version>0.3.3</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>
```

## Minimal Dependencies

```xml
<dependencies>
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
</dependencies>
```

## Optional Modules

| Module | Use it for |
| --- | --- |
| `ai-fabric-rag` | Retrieval-only RAG context building and evidence retrieval. |
| `ai-fabric-indexing` | Async indexing queue and worker. |
| `ai-fabric-data-sync` | HTTP/API data sync into AI Fabric indexing flows. |
| `ai-fabric-web` | Framework web/admin endpoints. |
| `ai-fabric-chat-session` | Conversation history, pending confirmations, pinned targets. |
| `ai-fabric-actions-connector` | File/catalog connector-backed actions. |
| `ai-fabric-actions-registry` | Database-backed action registry. |
| `ai-fabric-actions-registry-liquibase` | Liquibase schema for action registry. |
| `ai-fabric-pii` | PII detection and sanitization support. |
| `ai-fabric-governance` | Governance and compliance support. |
| `ai-fabric-relationship-query` | NL to relationship/query planning and action surface. |
| `ai-fabric-behavior` | LLM-backed behavior insight generation. |
| `ai-fabric-migration` | Backfill/migration jobs for existing app data. |
| `ai-fabric-onnx-starter` | Local ONNX embeddings. |
| `ai-fabric-vector-memory` | In-memory vector store for smoke/dev tests. |
| `ai-fabric-vector-qdrant` | Qdrant vector provider. |
| `ai-fabric-vector-pinecone` | Pinecone vector provider. |
| `ai-fabric-vector-weaviate` | Weaviate vector provider. |
| `ai-fabric-vector-milvus` | Milvus vector provider. |
| `ai-fabric-curated-default` | Default prompt/mode bundle. |
| `ai-fabric-curated-commerce` | Commerce prompt/mode bundle. |
| `ai-fabric-curated-support` | Support prompt/mode bundle. |

## Enable AI Fabric

```java
import ai.fabric.annotation.EnableAIInfrastructure;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@EnableAIInfrastructure
public class App {
    public static void main(String[] args) {
        SpringApplication.run(App.class, args);
    }
}
```

## Baseline Configuration

```yaml
ai:
  enabled: true
  config:
    default-file: ai-entity-config.yml
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
  vector-db:
    type: lucene
    lucene:
      index-path: ./data/lucene-vector-index-${OPENAI_EMBEDDING_DIMENSIONS:512}
  service:
    features:
      enable-generation: ${OPENAI_ENABLED:false}
      enable-embeddings: ${OPENAI_ENABLED:false}
      enable-search: true
      enable-rag: true
```

## Smoke Profile

Use a smoke profile for no-key local tests:

```yaml
ai:
  providers:
    llm-provider: smoke
    embedding-provider: smoke
  vector-db:
    type: memory
  service:
    features:
      enable-generation: true
      enable-embeddings: true
      enable-search: true
      enable-rag: true
```

## Build From Source

```bash
mvn -f ai-infrastructure-module/pom.xml clean install
```

