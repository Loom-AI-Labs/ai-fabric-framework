# AI Fabric Framework

AI Fabric Framework is an open-source Java/Spring Boot framework for building AI-enabled applications with orchestration, retrieval, provider integrations, vector stores, action execution, and reusable runtime primitives.

This repository contains the framework only. It does not include private managed-product code, deployment operations, customer configuration, or commercial product code.

**New to AI Fabric?** Start with the [user guides](docs/guides/README.md) (installation → concepts →
modules → use cases → examples → quickstart). Latest release: **0.3.1** — see the
[release notes](docs/release-notes/0.3.1.md).

## What Is Included

- orchestration and intent/action primitives
- RAG and retrieval abstractions
- indexing and data-sync modules
- Spring AI-backed provider module for cloud LLMs/embeddings, plus native and Spring AI ONNX local embeddings
- vector modules for Lucene, memory, Pinecone, Qdrant, Weaviate, and Milvus
- curated generic prompt/action packs
- optional runtime, web, governance, PII, migration, chat-session, behavior, and connector modules

## Requirements

- Java 21
- Maven 3.9+
- Spring Boot 4.1.x

## Install Locally

From the repository root:

```bash
mvn -f ai-infrastructure-module/pom.xml clean install
```

## Maven Usage

Artifacts are published to Maven Central under the `io.github.loom-ai-labs` group, so no
extra repository configuration is needed. The BOM artifact is:

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>io.github.loom-ai-labs</groupId>
      <artifactId>ai-fabric-bom</artifactId>
      <version>0.3.1</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>
```

Example dependency set:

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
    <artifactId>ai-fabric-onnx-starter</artifactId>
  </dependency>

  <dependency>
    <groupId>io.github.loom-ai-labs</groupId>
    <artifactId>ai-fabric-vector-lucene</artifactId>
  </dependency>
</dependencies>
```

## Minimal Spring Boot Entry Point

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

Minimum example configuration:

```yaml
ai:
  providers:
    llm-provider: openai
    embedding-provider: onnx
    openai:
      api-key: ${OPENAI_API_KEY}
      base-url: https://api.openai.com/v1
      model: gpt-4o-mini
    onnx:
      model-path: ${AI_FABRIC_ONNX_MODEL_PATH:./models/embeddings/all-MiniLM-L6-v2.onnx}
      tokenizer-path: ${AI_FABRIC_ONNX_TOKENIZER_PATH:./models/embeddings/tokenizer.json}
  vector-db:
    type: lucene
```

See `examples/minimal-spring-boot` for a small starter project.

## Local ONNX Assets

The ONNX starter supports local embeddings, but this repository does not commit third-party model binaries or tokenizer assets. To use the default local embedding setup, download the model assets first:

```bash
cd ai-infrastructure-module
./scripts/download-onnx-model.sh
```

Then point your application to the downloaded assets:

```bash
export AI_FABRIC_ONNX_MODEL_PATH="$(pwd)/models/embeddings/all-MiniLM-L6-v2.onnx"
export AI_FABRIC_ONNX_TOKENIZER_PATH="$(pwd)/models/embeddings/tokenizer.json"
```

## Repository Layout

```text
ai-infrastructure-module/          Maven multi-module framework reactor
examples/minimal-spring-boot/      Minimal consumer application
docs/                              Release and consumer docs
```

## Release Status

This is an early release line (`0.x`). APIs may still change before a stable `1.0.0`.

The current release is:

```text
0.2.0
```

> Note: `0.2.0` renames coordinates and Java packages (`com.ai.infrastructure.*` → `ai.fabric.*`,
> `ai-infrastructure-*` → `ai-fabric-*`) and is **not** a drop-in upgrade from `0.1.0`.

## License

Apache License 2.0. See `LICENSE`.
