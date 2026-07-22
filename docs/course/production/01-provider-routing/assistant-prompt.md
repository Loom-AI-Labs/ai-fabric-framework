# Coding Assistant Prompt: Implement PROD-01 Provider Routing

Implement AI Fabric course lesson `PROD-01` in the standalone learner repository.

## Repository And Refs

- Repository: `https://github.com/Loom-AI-Labs/ai-fabric-course-support-assistant`
- Start from immutable tag: `course-0.3.3-06-tested-solution`
- Expected solution behavior: `course-0.3.3-p01-provider-routing`
- Framework contract: AI Fabric `0.3.3`, Java 21, Spring Boot 4.1.x

Do not edit or depend on a local AI Fabric source checkout. Use the Maven Central artifacts already
declared by the learner application.

## Objective

Add explicit purpose-specific LLM configuration and proof while preserving every Core capability:

1. configure `ai.providers.orchestration` and `ai.providers.generation` separately in the OpenAI
   profile;
2. keep ONNX embeddings and Lucene vectors local;
3. keep `ai.providers.enable-fallback=false`;
4. create distinct, explicitly test-only recording providers for orchestration and generation;
5. test provider/model selection through `AICoreService` and `LlmPurpose`;
6. test that a failed generation provider is not replaced by the orchestration provider;
7. expose safe purpose provider/model posture through `/api/demo/health`;
8. update packaged smoke assertions, request examples, README, and checkpoint identity.

## Required Configuration

Use environment references, never literal secrets:

```yaml
orchestration:
  llm-provider: ${AI_ORCHESTRATION_PROVIDER:openai}
  model: ${AI_ORCHESTRATION_MODEL:${OPENAI_MODEL:gpt-4o-mini}}
generation:
  llm-provider: ${AI_GENERATION_PROVIDER:openai}
  model: ${AI_GENERATION_MODEL:${OPENAI_MODEL:gpt-4o-mini}}
openai:
  enabled: ${OPENAI_ENABLED:true}
  api-key: ${OPENAI_API_KEY:}
```

Keep embedding provider `onnx`, vector type `lucene`, and fallback false.

## Test Design

- Keep the response controller/recording fixture separate from the two `AIProvider` adapters.
- Name providers so no report can mistake them for OpenAI.
- Capture provider name, request model, prompt/messages, and call count.
- Do not route by matching user text.
- Do not add a runtime canned provider.
- Existing RAG/action/memory/security tests must continue to exercise real framework wiring.
- Add `PurposeRoutingIntegrationTest` with one success path per purpose and one no-fallback failure.
- Add/adjust health tests so API keys, tokens, and prompt content are absent.

## Safe Health Contract

Preserve existing fields and add:

```text
provider.orchestration
provider.orchestrationModel
provider.generation
provider.generationModel
```

When LLM generation is disabled, report those four values as `disabled`. Do not report configured
defaults as if a runtime call occurred.

## Verification

Run tests normally:

```bash
./mvnw --batch-mode --no-transfer-progress clean verify
./scripts/download-onnx-model.sh
COURSE_SMOKE_USE_EXISTING_JAR=true ./scripts/smoke-packaged.sh
jq . target/course-release-evidence/packaged-smoke-summary.json
```

Expected mandatory evidence:

- 42 tests pass with no skips or external keys;
- packaged smoke status is `PASS`;
- checkpoint is `course-0.3.3-p01-provider-routing`;
- local health says LLM purposes disabled, embeddings `onnx`, vectors `lucene`, fallback false;
- no credential is present in source, logs, reports, or response payloads.

Do not run an OpenAI call unless `OPENAI_API_KEY` is already supplied through a private environment.
If it is absent, report that optional gate as `SKIPPED: credential not supplied`; do not invent a
pass.

## Scope Guard

Do not change framework code, business authorization, tenant semantics, prompt policy, action
schemas, or conversation ownership. Do not add provider fallback. Do not commit a key or a generated
local model/index artifact.

Finish with a concise change summary, exact test/smoke results, and a separate statement for any
optional live-provider gate.

