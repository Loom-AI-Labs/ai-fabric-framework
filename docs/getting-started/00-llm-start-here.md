# LLM Start Here

Use this file as the first context block for any coding assistant session that touches AI Fabric.

## Current Baseline

- AI Fabric release: `0.3.3`
- Java: `21`
- Spring Boot: `4.1.x`
- Maven group: `io.github.loom-ai-labs`
- Enable framework auto-configuration with `@EnableAIInfrastructure`.
- Prefer smoke-profile tests for local no-key verification.
- Use real provider tests only when the required API keys are explicitly available.

## Framework Philosophy

AI Fabric exists to make Java applications AI-enabled without moving business logic into the
frontend or into ungoverned prompt code.

When implementing a feature:

- Let AI Fabric orchestration, RAG, actions, chat session, and policy layers make AI decisions.
- Do not route user intent with frontend keyword matching.
- Do not fake intelligence with deterministic UI shortcuts.
- Do not hide LLM/provider failures behind silent fallback unless the selected guide explicitly says
  the fallback is the intended product behavior.
- Keep app policies in user-friendly domain text. Do not turn policy docs into prompt-only control
  files unless the app explicitly needs that.
- Add tests for every framework-facing integration change.

## Before Editing Code

Read only the smallest set of files needed:

| Task | Read |
| --- | --- |
| New app setup | `01-choose-your-path.md`, `02-installation.md` |
| Search/indexing | `03-first-semantic-search.md`, `09-vector-storage-lucene.md` |
| RAG answers | `04-first-rag-chat.md`, `10-security-access-policy.md` |
| Actions/confirmations | `05-first-governed-action.md`, `06-chat-session-memory.md` |
| Provider config | `07-real-provider-openai.md`, `08-local-onnx-embeddings.md` |
| Release readiness | `11-testing-and-verification.md`, `13-production-checklist.md` |
| Real examples | `12-real-apps-map.md` |

## Coding Rules

- Identify the application module, AI Fabric modules, providers, and vector store before changing code.
- Reuse patterns from `examples/real-apps` before inventing a new integration style.
- Use `ai-fabric-chat-session` for follow-up turns instead of asking the UI to resend history.
- Use `@AIAction` for domain actions and require confirmation for side effects.
- Use `@AICapable`, `@AISearchable`, and `@AIContext` or `ai-entity-config.yml` to define
  searchable data.
- Use metadata filters and access policies for tenant/user boundaries.
- Keep raw app events raw. Let behavior analysis infer meaning.
- Keep action results domain-shaped for UI display.

## Verification Rules

For local no-key changes:

```bash
mvn -f ai-infrastructure-module/pom.xml test
```

For one real app:

```bash
mvn -f examples/real-apps/chat-capabilities-demo/pom.xml test
mvn -f examples/real-apps/chat-capabilities-demo/pom.xml spring-boot:run -Dspring-boot.run.profiles=smoke
```

For real provider checks, only run after keys are loaded:

```bash
OPENAI_ENABLED=true OPENAI_API_KEY=... mvn -f ai-infrastructure-module/pom.xml -pl integration-Testing/integration-tests test
```

If a test cannot be run, document why and what remains unverified.

