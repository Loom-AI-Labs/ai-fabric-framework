# AI Fabric 0.3.3 minimal semantic retrieval

This standalone Spring Boot application downloads AI Fabric `0.3.3` from Maven Central. It does
not depend on the AI Fabric source tree or locally installed reactor modules. On startup it:

1. creates one embedding and indexes one document with AI Fabric's Lucene vector store;
2. embeds one semantic/RAG-style question; and
3. prints the best matching document.

## Requirements

- Java 21 (AI Fabric `0.3.3` classes target Java 21)
- Internet access on the first run, so Maven can download public dependencies
- No system Maven installation is required; the included Maven wrapper downloads Maven

AI Fabric `0.3.3` manages Spring Boot `4.1.0`, which this example uses directly.

## Run it

From a clean checkout on macOS or Linux:

```bash
cd examples/minimal-rag-quickstart
./mvnw spring-boot:run
```

On Windows PowerShell:

```powershell
cd examples/minimal-rag-quickstart
.\mvnw.cmd spring-boot:run
```

The final output includes:

```text
Indexed document: AI Fabric uses vector embeddings to retrieve relevant documents for semantic search and RAG.
Semantic query:  How can I retrieve documents for a RAG application?
Top result:      AI Fabric uses vector embeddings to retrieve relevant documents for semantic search and RAG.
```

## Provider configuration

The example is intentionally runnable without an account or API key. `DemoEmbeddingProvider` is a
small deterministic `EmbeddingProvider` implementation included in the application. It represents
eight concepts as a vector, making the indexing and retrieval flow reproducible and fully offline.
It is suitable only for demonstrating the AI Fabric API, not for production semantic quality.

In a real application, replace the `embeddingProvider()` bean with an AI Fabric embedding-provider
artifact and configure that provider through environment variables. For example, keep secrets out
of source control and reference an environment variable from Spring configuration:

```yaml
ai:
  providers:
    embedding-api-key: ${EMBEDDING_API_KEY}
```

Consult the chosen provider artifact's `0.3.3` documentation for its exact property names. This
quickstart itself reads no credentials and contains no private keys or machine-specific paths.

## Verify the standalone dependency boundary

The POM has no AI Fabric parent and no relative module paths. To force a fresh dependency check,
use a new Maven cache directory:

```bash
./mvnw -Dmaven.repo.local=target/clean-maven-cache clean verify
```
