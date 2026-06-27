# 1. Installation & Setup

This guide gets AI Fabric onto your build path and your first AI-enabled bean wired up.

## Requirements

- **Java 21**
- **Maven 3.9+**
- **Spring Boot 4.1.x**

AI Fabric is distributed as ordinary Maven artifacts on **Maven Central** under the group
`io.github.loom-ai-labs` — no extra repository configuration is needed.

## Step 1 — Import the BOM

The BOM (`ai-fabric-bom`) manages the versions of every AI Fabric module so you never specify
versions on individual dependencies. Import it in `dependencyManagement`:

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>io.github.loom-ai-labs</groupId>
      <artifactId>ai-fabric-bom</artifactId>
      <version>0.3.0</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>
```

## Step 2 — Add the starter and the pieces you need

The **starter** brings in the core auto-configuration. Then add one **LLM provider**, one
**embedding provider**, and one **vector store** (you can change these later via configuration):

```xml
<dependencies>
  <!-- Core auto-configuration -->
  <dependency>
    <groupId>io.github.loom-ai-labs</groupId>
    <artifactId>ai-fabric-starter</artifactId>
  </dependency>

  <!-- An LLM provider (choose one or more) -->
  <dependency>
    <groupId>io.github.loom-ai-labs</groupId>
    <artifactId>ai-fabric-provider-spring-ai</artifactId>
  </dependency>

  <!-- Local embeddings via ONNX (no API calls) -->
  <dependency>
    <groupId>io.github.loom-ai-labs</groupId>
    <artifactId>ai-fabric-onnx-starter</artifactId>
  </dependency>

  <!-- A vector store (Lucene is local, file-based) -->
  <dependency>
    <groupId>io.github.loom-ai-labs</groupId>
    <artifactId>ai-fabric-vector-lucene</artifactId>
  </dependency>
</dependencies>
```

See the [Modules Reference](03-modules.md) for the full catalog of providers, vector stores, and
feature modules.

## Step 3 — Enable the framework

Annotate your Spring Boot application with `@EnableAIInfrastructure`:

```java
import ai.fabric.annotation.EnableAIInfrastructure;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@EnableAIInfrastructure
public class ExampleApplication {
    public static void main(String[] args) {
        SpringApplication.run(ExampleApplication.class, args);
    }
}
```

## Step 4 — Configure providers and the vector store

A minimal `application.yml`:

```yaml
ai:
  providers:
    llm-provider: openai          # which AIProvider to use for generation
    embedding-provider: onnx      # which EmbeddingProvider to use
    openai:
      api-key: ${OPENAI_API_KEY}
      base-url: https://api.openai.com/v1
      model: gpt-4o-mini
    onnx:
      model-path: ${AI_FABRIC_ONNX_MODEL_PATH:./models/embeddings/all-MiniLM-L6-v2.onnx}
      tokenizer-path: ${AI_FABRIC_ONNX_TOKENIZER_PATH:./models/embeddings/tokenizer.json}
  vector-db:
    type: lucene                  # local, file-based vector store
```

Full property reference: [Configuration Reference](04-configuration.md).

## Local ONNX assets (for local embeddings)

The ONNX starter runs embeddings locally with no API calls, but the framework does **not** ship
third-party model binaries. If you use the default local embedding setup, download the model first:

```bash
cd ai-infrastructure-module
./scripts/download-onnx-model.sh
```

Then point `ai.providers.onnx.model-path` / `tokenizer-path` at the downloaded files (the defaults
above already match the script's output location).

> **Evaluating without keys or models?** Use the `smoke` profile shown in the
> [guides index](README.md) and [Quickstart](07-quickstart.md): it swaps in a deterministic local LLM provider,
> deterministic in-process embeddings (no ONNX file), an in-memory vector store, and H2.

## Building from source (optional)

To build and install the framework into your local Maven repository:

```bash
git clone https://github.com/loom-ai-labs/ai-fabric-framework.git
cd ai-fabric-framework
mvn -f ai-infrastructure-module/pom.xml clean install
```

## Next

→ [Understanding AI Fabric](02-understanding-ai-fabric.md)
