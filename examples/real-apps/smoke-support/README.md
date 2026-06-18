# smoke-support

Shared test-support module that lets every app in `examples/real-apps` **boot with no API keys and no
external services** under the Spring `smoke` profile. It is intended for smoke/CI checks ("does the app
start and wire up?"), not for producing real AI results.

## What it provides

- **`SmokeAiProvider`** — a local `ai.fabric.provider.AIProvider` (name `smoke`) that returns a
  deterministic response instead of calling an external model.
- **`SmokeEmbeddingProvider`** — a deterministic, in-process `ai.fabric.embedding.EmbeddingProvider`
  (name `smoke`, 384-dim) that needs no ONNX model file.
- Transitive **`ai-fabric-vector-memory`** (in-memory vector store) and **H2** (in-memory database).
- A bundled **`application-smoke.yml`** that points the framework's selectors at the local providers:

  ```yaml
  ai:
    providers:
      llm-provider: smoke
      embedding-provider: smoke
    vector-db:
      type: memory
  ```

  Because it is a profile-specific document on the classpath, it overrides each app's base
  `application.yml` when the `smoke` profile is active. An app that needs extra overrides (for example
  `cloud-qdrant-openai-vector-search`, which swaps Postgres for H2) ships its own
  `application-smoke.yml`, which fully replaces this shared one for that app.

## Usage

Every example app already depends on this module. Activate the profile when launching:

```bash
# via the Spring Boot Maven plugin
mvn -pl <app> spring-boot:run -Dspring-boot.run.profiles=smoke

# or with the packaged jar
java -jar <app>/target/<app>.jar --spring.profiles.active=smoke
```

Run without the profile to use the app's real configuration (which may require API keys / services).
