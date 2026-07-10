# Testing And Verification

AI Fabric changes need layered verification: unit tests for contract behavior, smoke tests for
no-key local operation, and real provider tests when credentials are available.

## Framework Unit Tests

```bash
mvn -f ai-infrastructure-module/pom.xml test
```

Use this for framework code changes and module cleanup.

## Focused Module Tests

```bash
mvn -f ai-infrastructure-module/pom.xml -pl ai-fabric-core test
mvn -f ai-infrastructure-module/pom.xml -pl ai-fabric-rag test
mvn -f ai-infrastructure-module/pom.xml -pl ai-fabric-chat-session test
```

## Real App Tests

```bash
mvn -f examples/real-apps/chat-capabilities-demo/pom.xml test
mvn -f examples/real-apps/ai-fabric-account-resolver/pom.xml test
mvn -f examples/real-apps/behavior-churn-signals/pom.xml test
mvn -f examples/real-apps/tenant-knowledge-portal/pom.xml test
```

## Smoke Runtime

```bash
mvn -f examples/real-apps/chat-capabilities-demo/pom.xml spring-boot:run -Dspring-boot.run.profiles=smoke
```

Smoke profile should not require API keys or model files.

## Real Provider Runtime

```bash
OPENAI_ENABLED=true \
OPENAI_API_KEY=... \
OPENAI_MODEL=gpt-4o-mini \
OPENAI_EMBEDDING_MODEL=text-embedding-3-small \
OPENAI_EMBEDDING_DIMENSIONS=512 \
mvn -f examples/real-apps/chat-capabilities-demo/pom.xml spring-boot:run
```

## What Needs LLM Keys

- orchestration intent generation
- RAG answer generation
- smart suggestions
- behavior insight generation
- agentic UI component recommendation
- real provider matrix tests

## What Should Not Need LLM Keys

- action handler unit tests
- vector provider contract tests with deterministic embeddings
- smoke profile boot
- YAML/entity config loading
- access policy unit tests
- chat session persistence tests

## Release Gate Checklist

- unit tests pass
- focused changed-module tests pass
- affected real apps compile
- smoke profile boots
- real provider smoke passes when keys are available
- docs mention any manual/non-P0 external provider proof

