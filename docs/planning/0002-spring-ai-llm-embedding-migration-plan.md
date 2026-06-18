# ADR 0002 - Spring AI LLM and embedding execution

- **Status:** Implemented for framework code; release verification in progress
- **Date:** 2026-06-18
- **Decision owner:** AI Fabric framework
- **Scope:** LLM providers and embedding providers
- **Explicit non-goals:** Vector providers stay native AI Fabric providers. AI Fabric native ONNX is not replaced.

## Decision

AI Fabric now uses Spring AI as the commodity execution layer for cloud LLM and embedding calls.

```text
AI Fabric callers
  -> AICoreService / AIEmbeddingService
  -> AIProviderManager / embedding fallback and cache
  -> ai-fabric-provider-spring-ai
  -> Spring AI ChatModel / EmbeddingModel
  -> Spring AI provider integrations and vendor SDKs
```

AI Fabric still owns the product and framework contract:

- provider routing and fallback policy
- purpose-specific model selection
- endpoint/profile overrides
- transient file URL validation and fail-closed policy
- request metadata, usage metadata, and provider status
- embedding cache, fallback, batch execution, and dimensions
- orchestration, RAG, actions, governance, and vector providers

Spring AI owns model execution for supported cloud chat and embedding providers, plus the opt-in
`spring-ai-onnx` local embedding path.

## Version rail

Use the latest rail for this release:

- Java: `21`
- Spring Boot: `4.1.0`
- Spring AI: `2.0.0`
- Spring Cloud Context: `5.0.2`

Evidence:

- `ai-infrastructure-module/pom.xml` sets `maven.compiler.source` and `maven.compiler.target` to `21`.
- `ai-infrastructure-module/pom.xml` sets `spring-boot.version` to `4.1.0`.
- `ai-infrastructure-module/pom.xml` imports `org.springframework.ai:spring-ai-bom:2.0.0`.
- Boot 4 split-package migrations are reflected in imports such as `org.springframework.boot.restclient.RestTemplateBuilder`, `org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration`, `org.springframework.boot.persistence.autoconfigure.EntityScan`, and `org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest`.

## Module shape

The active provider modules are:

```text
ai-infrastructure-module/providers/ai-fabric-provider-spring-ai
ai-infrastructure-module/providers/ai-fabric-onnx-starter
```

The old native cloud LLM/embedding provider modules have been retired from the workspace:

```text
ai-fabric-provider-openai
ai-fabric-provider-azure
ai-fabric-provider-anthropic
ai-fabric-provider-gemini
ai-fabric-provider-cohere
```

The root reactor now includes `providers/ai-fabric-provider-spring-ai` and does not include the retired cloud modules. Native ONNX remains in the reactor as `providers/ai-fabric-onnx-starter`; Spring AI ONNX is added beside it as an explicit provider named `spring-ai-onnx`.

## Provider support matrix

| Provider name | Chat | Embedding | Execution path | Release note |
| --- | --- | --- | --- | --- |
| `openai` | Yes | Yes | Spring AI OpenAI | Supports OpenAI-compatible base URL/API key and per-request model options. |
| `azure` | Yes | Yes | Spring AI OpenAI/Azure options | Supports endpoint, deployment, API version, and native Azure flag where applicable. |
| `anthropic` | Yes | No | Spring AI Anthropic | Spring AI 2.0 path is chat-only for AI Fabric's current contract. |
| `gemini` | Yes | Yes | Spring AI Google GenAI | Supports chat and text embeddings. |
| `cohere` | No | No | Not active | No GA Spring AI 2.0 Cohere starter is used in this release. Reintroduce only when Spring AI exposes a supported path or a strategic exception is approved. |
| `onnx` | No | Yes | AI Fabric native ONNX | Retained intentionally; not replaced by Spring AI. |
| `spring-ai-onnx` | No | Yes | Spring AI Transformers ONNX | Opt-in local embedding provider beside native ONNX; no API key required. |

## Code evidence

Core contracts preserved:

- `ai-infrastructure-module/ai-fabric-core/src/main/java/ai/fabric/provider/AIProvider.java`
- `ai-infrastructure-module/ai-fabric-core/src/main/java/ai/fabric/embedding/EmbeddingProvider.java`
- `ai-infrastructure-module/ai-fabric-core/src/main/java/ai/fabric/core/AICoreService.java`
- `ai-infrastructure-module/ai-fabric-core/src/main/java/ai/fabric/core/AIEmbeddingService.java`
- `ai-infrastructure-module/ai-fabric-core/src/main/java/ai/fabric/provider/AIProviderManager.java`
- `ai-infrastructure-module/ai-fabric-core/src/main/java/ai/fabric/provider/ProviderRequestOverrideSupport.java`
- `ai-infrastructure-module/ai-fabric-core/src/main/java/ai/fabric/provider/TransientInputSupport.java`

Spring AI implementation:

- `ai-infrastructure-module/providers/ai-fabric-provider-spring-ai/pom.xml`
- `ai-infrastructure-module/providers/ai-fabric-provider-spring-ai/src/main/java/ai/fabric/provider/springai/SpringAiProviderAutoConfiguration.java`
- `ai-infrastructure-module/providers/ai-fabric-provider-spring-ai/src/main/java/ai/fabric/provider/springai/SpringAiChatProvider.java`
- `ai-infrastructure-module/providers/ai-fabric-provider-spring-ai/src/main/java/ai/fabric/provider/springai/SpringAiEmbeddingProvider.java`
- `ai-infrastructure-module/providers/ai-fabric-provider-spring-ai/src/main/java/ai/fabric/provider/springai/SpringAiModelResolver.java`
- `ai-infrastructure-module/providers/ai-fabric-provider-spring-ai/src/main/java/ai/fabric/provider/springai/SpringAiPromptMapper.java`
- `ai-infrastructure-module/providers/ai-fabric-provider-spring-ai/src/main/java/ai/fabric/provider/springai/SpringAiProviderFamily.java`
- `ai-infrastructure-module/providers/ai-fabric-provider-spring-ai/src/main/java/ai/fabric/provider/springai/ProviderMetrics.java`

Spring AI adapter tests:

- `ai-infrastructure-module/providers/ai-fabric-provider-spring-ai/src/test/java/ai/fabric/provider/springai/SpringAiPromptMapperTest.java`
- `ai-infrastructure-module/providers/ai-fabric-provider-spring-ai/src/test/java/ai/fabric/provider/springai/SpringAiProviderAdapterTest.java`

Integration-test compile migrations:

- `ai-infrastructure-module/integration-Testing/integration-tests/src/test/java/ai/fabric/it/EmbeddingMultilanguageIntegrationTest.java`
- `ai-infrastructure-module/integration-Testing/integration-tests/src/test/java/ai/fabric/it/ONNXEmbeddingIntegrationTest.java`
- `ai-infrastructure-module/integration-Testing/relationship-query-integration-tests/src/test/java/ai/fabric/relationship/it/config/BackendEnvTestConfiguration.java`
- `ai-infrastructure-module/integration-Testing/relationship-query-integration-tests/pom.xml`
- `ai-infrastructure-module/integration-Testing/behavior-integration-tests/pom.xml`

## Design details

### Model resolver and cache

`SpringAiModelResolver` is the central bridge between AI Fabric provider configuration and Spring AI model instances.

It caches model clients by connection identity, not by every request:

- provider family
- API key fingerprint
- base URL
- deployment name where relevant
- API version where relevant
- Azure native mode
- timeout
- Spring AI ONNX model URI, tokenizer URI, cache setting, cache directory, GPU device id, and output name

Per-request settings remain request options:

- model
- temperature
- max tokens
- embedding dimensions
- Azure deployment/API version when expressed as options

This keeps dynamic endpoint flexibility without keeping native provider implementations.

### Per-request endpoint override

AI Fabric already carries trusted provider connection overrides through `ProviderRequestOverrideSupport`.

The Spring AI design keeps that capability by resolving overrides into model cache keys and Spring AI options:

```text
AICoreService purpose defaults / request override
  -> ProviderRequestOverrideSupport
  -> SpringAiModelResolver
  -> cached ChatModel or EmbeddingModel
  -> per-request ChatOptions or EmbeddingOptions
```

This handles internal purpose routing and managed endpoint profiles without exposing raw endpoint switching as an uncontrolled user feature.

### Transient file URL policy

Transient file policy remains an AI Fabric policy layer.

`SpringAiPromptMapper`:

- validates transient `FILE_URL` inputs through `TransientInputSupport`
- rejects unsupported media before calling Spring AI
- maps supported transient media to Spring AI `Media`
- adds document-usage instructions without exposing raw file URLs in logs or persistent data

`AIProviderManager` still owns fail-closed behavior and no-fallback semantics for transient file inputs.

### Embeddings

`SpringAiEmbeddingProvider` implements AI Fabric's `EmbeddingProvider` contract and delegates execution to Spring AI `EmbeddingModel`.

AI Fabric keeps:

- `AIEmbeddingService` cache
- fallback provider choice
- batch embedding call shape
- service/provider processing metrics
- model dimension reporting

Spring AI handles provider-specific embedding request execution.

### ONNX

Native ONNX remains in `providers/ai-fabric-onnx-starter` and stays the default local embedding provider.

Spring AI ONNX is available beside it as `spring-ai-onnx` through `ai-fabric-provider-spring-ai`.
It uses Spring AI's `TransformersEmbeddingModel` and the `spring-ai-transformers` dependency. This is useful for Spring AI parity, framework experimentation, and users who want Spring AI's bundled transformer path.

It is not the default and does not replace AI Fabric native ONNX, because native ONNX already carries AI Fabric's current local embedding behavior and tests.

### Cohere

Cohere is not active in the Spring AI execution path for this release.

Do not keep a private native Cohere implementation just to preserve the old matrix. Add Cohere back only if one of these becomes true:

- Spring AI ships and documents a supported Cohere model integration.
- AI Fabric chooses a strategic provider-specific exception.
- Cohere is reached through another supported Spring AI provider path such as Bedrock or OCI, with contract tests.

## Release checklist

Required before release:

- Full framework source compile is green.
- Full framework test compile is green, including integration-test sources, with integration tests not executed.
- Non-integration framework unit tests are green.
- Spring AI adapter tests are green.
- `rg` confirms no active framework code imports old native OpenAI SDK packages.
- `rg` confirms no active framework POM references retired native provider artifacts.
- Documentation does not point users to retired provider modules.
- Startup/provider diagnostics clearly report unsupported Cohere and Anthropic embeddings.
- Release notes call out the Boot 4.1/Spring AI 2.0 upgrade as a breaking platform upgrade.

Commands:

```bash
mvn -f ai-infrastructure-module/pom.xml compile
mvn -f ai-infrastructure-module/pom.xml test-compile
mvn -f ai-infrastructure-module/pom.xml -pl providers/ai-fabric-provider-spring-ai -am -Dtest='SpringAi*' -Dsurefire.failIfNoSpecifiedTests=false test
mvn -f ai-infrastructure-module/pom.xml test
git diff --check
```

When running unit tests for release, exclude integration-test execution modules if live provider/database tests are not intended:

```bash
mvn -f ai-infrastructure-module/pom.xml \
  -pl '!integration-Testing/integration-tests,!integration-Testing/relationship-query-integration-tests,!integration-Testing/chat-session-integration-tests,!integration-Testing/behavior-integration-tests,!integration-Testing/testcontainers-support' \
  test
```

If Maven negative project selection is unreliable in a local shell, use an explicit non-integration module list.

## Known release risks

- Spring AI 2.0 does not cover every old AI Fabric provider feature one-for-one. The adapter intentionally fails explicitly where Spring AI does not expose the feature.
- Boot 4 uses new split artifacts and package names for tests, REST clients, JDBC, JPA, and Jackson. Compile gates must include test sources.
- Boot 4 uses Jackson 3 for auto-configuration hooks. Framework code that still directly uses Jackson 2 APIs should be reviewed separately if it becomes part of Boot-managed web serialization.
- Anthropic embeddings and Cohere are not available through this active provider module.
- Live-provider smoke tests still require real credentials and should be run separately from default CI.
