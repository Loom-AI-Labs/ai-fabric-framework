# smoke-support

## Role

`smoke-support` is a shared test-support module for `examples/real-apps`. It is not a product demo.

It lets real apps boot with no API keys and no external services under the Spring `smoke` profile.
Use it for CI and local release smoke checks: "does this app start and wire the AI Fabric pieces?"

## AI Fabric Capabilities Proved

- A deterministic local `AIProvider` can satisfy LLM-provider selection for smoke runs.
- A deterministic local `EmbeddingProvider` can satisfy embedding-provider selection without ONNX or
  cloud services.
- The in-memory vector provider can satisfy `VectorDatabaseService` for startup and lightweight app
  checks.
- A shared profile can keep real-app smoke boot consistent across the suite.

## What It Provides

- `SmokeAiProvider`: local `ai.fabric.provider.AIProvider` named `smoke`.
- `SmokeEmbeddingProvider`: deterministic in-process `ai.fabric.embedding.EmbeddingProvider` named
  `smoke`, 384 dimensions.
- Transitive `ai-fabric-vector-memory`.
- Transitive H2.
- `application-smoke.yml` with provider selectors:

```yaml
ai:
  providers:
    llm-provider: smoke
    embedding-provider: smoke
  vector-db:
    type: memory
```

## Runtime Posture

- no external LLM
- no external embedding provider
- no model files
- no external vector database
- no external database

Apps can still ship their own `application-smoke.yml` when they need extra overrides.

## Usage

Most real apps already depend on this module. Activate the profile when launching:

```bash
mvn -pl <app> spring-boot:run -Dspring-boot.run.profiles=smoke
```

or:

```bash
java -jar <app>/target/<app>.jar --spring.profiles.active=smoke
```

## Validate

Run the focused tests:

```bash
mvn -B -V --no-transfer-progress -f examples/real-apps/pom.xml -pl smoke-support test
```

## What This Module Does Not Cover

- Real model behavior.
- Semantic quality.
- Real vector provider behavior.
- Real API keys or provider matrix validation.

Those belong to real-provider integration tests and opt-in real app profiles.
