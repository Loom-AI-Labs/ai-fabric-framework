---
id: prod-01
slug: provider-routing
title: Provider Routing And Purpose-Specific Models
track: production
order: 1
durationMinutes: 80
availability: preview
courseVersion: 0.3.3-course.1-beta
frameworkVersion: 0.3.3
frameworkTag: ai-fabric-framework-v0.3.3
courseSourceTag: ai-fabric-course-v0.3.3.1
starterRef: course-0.3.3-06-tested-solution
solutionRef: course-0.3.3-p01-provider-routing
requiresOpenAi: false
requiresDocker: false
optionalProviderExercises:
  - openai
sourcePaths:
  - docs/course/production/01-provider-routing/notebooklm/AI_FABRIC_PROVIDER_ARCHITECTURE_PURPOSE_ROUTING_NOTEBOOKLM_SCRIPT.md
  - docs/getting-started/07-real-provider-openai.md
  - docs/getting-started/08-local-onnx-embeddings.md
  - docs/Framework-Dev-Guides/runtime-integration/SPRING_AI_PROVIDER_INTEGRATION_GUIDE.md
  - ai-infrastructure-module/ai-fabric-core/src/main/java/ai/fabric/core/LlmPurpose.java
  - ai-infrastructure-module/ai-fabric-core/src/main/java/ai/fabric/config/AIProviderConfig.java
  - ai-infrastructure-module/ai-fabric-core/src/main/java/ai/fabric/core/AICoreService.java
  - ai-infrastructure-module/ai-fabric-core/src/main/java/ai/fabric/provider/AIProviderManager.java
  - ai-infrastructure-module/providers/ai-fabric-provider-spring-ai/src/main/java/ai/fabric/provider/springai/SpringAiModelResolver.java
theoryVideoIds: []
assistant:
  mode: implement
  implementationPrompt: assistant-prompt.md
  reviewPrompt: assistant-review-prompt.md
  validationStatus: passed
knowledgeCheck:
  source: knowledge-check.yml
  required: true
  passingScorePercent: 80
video:
  status: script-ready
  generator: NotebookLM
  purpose: pre-lesson-theory
  placement: before-lab
  targetDurationMinutes: 10
  title: Provider Architecture And Purpose-Specific Models
  publicUrl: null
  transcript: notebooklm/AI_FABRIC_PROVIDER_ARCHITECTURE_PURPOSE_ROUTING_NOTEBOOKLM_SCRIPT.md
  sourceManifest: notebooklm/source-manifest.yml
---

# Provider Routing And Purpose-Specific Models

## Start Here

Your Core application can retrieve approved evidence, generate grounded answers, discover governed
actions, remember conversations, enforce tenant boundaries, and prove those behaviors through a
packaged smoke. This lesson changes one production concern: which model handles each kind of work.

You will keep embeddings local with ONNX, keep vectors local with Lucene, and configure two LLM
purposes independently:

- `ORCHESTRATION` for intent extraction, classification, planning, and action selection;
- `GENERATION` for evidence-grounded answers and other user-facing narrative.

The required lab is keyless. Two explicitly test-only recording providers prove the selected
provider and model for each purpose. The optional OpenAI section uses the same application
configuration with a real key supplied at runtime.

> **Preview lab with a verified checkpoint.** Start from
> [`course-0.3.3-06-tested-solution`](https://github.com/Loom-AI-Labs/ai-fabric-course-support-assistant/tree/course-0.3.3-06-tested-solution)
> and compare your result with
> [`course-0.3.3-p01-provider-routing`](https://github.com/Loom-AI-Labs/ai-fabric-course-support-assistant/tree/course-0.3.3-p01-provider-routing).
> The code checkpoint is tested and immutable. The lesson remains preview until its theory recording
> is reviewed and published.

## What You Will Prove

By the end, you will be able to show that:

1. orchestration and answer generation can resolve different provider/model settings;
2. ONNX embedding configuration is independent from either LLM purpose;
3. local retrieval remains available without a cloud key;
4. provider diagnostics report effective purpose routes without exposing credentials;
5. `enable-fallback=false` prevents a failed generation route from switching to another provider;
6. a real OpenAI exercise is optional for you and separately identified from deterministic proof.

## The Provider Map

Keep these roles separate:

```text
new support message
  |
  +-> ORCHESTRATION LLM
  |     intent, action selection, parameters, confirmation interpretation
  |
  +-> ONNX embedding provider
  |     query and application-evidence vectors
  |
  +-> Lucene vector provider
  |     store, filter, and retrieve semantic evidence
  |
  +-> GENERATION LLM
        answer from the approved evidence context
```

`LlmPurpose` selects an LLM configuration. It does not replace the embedding provider, vector
provider, mode policy, action authorization, or application database.

## Key Posture

| Path | External key | What it proves |
| --- | --- | --- |
| deterministic `test` profile | none | purpose-to-provider/model routing and no fallback |
| packaged `local` profile | none | real ONNX, Lucene, application wiring, HTTP behavior |
| optional `openai` profile | `OPENAI_API_KEY` | current credential, network, model, provider, and response compatibility |

Never describe the first two rows as live OpenAI evidence. Never make the third row pass by returning
a deterministic answer after OpenAI failed.

## Prerequisites

- Java 21
- Maven 3.9+ or the included wrapper
- `curl` and `jq` for the packaged smoke
- Docker is not required
- OpenAI is optional

Start from a clean checkpoint:

```bash
git clone https://github.com/Loom-AI-Labs/ai-fabric-course-support-assistant.git
cd ai-fabric-course-support-assistant
git switch --detach course-0.3.3-06-tested-solution
git switch -c lesson/prod-01-provider-routing
./mvnw --batch-mode --no-transfer-progress clean verify
```

Expected baseline:

```text
BUILD SUCCESS
Tests run: 40, Failures: 0, Errors: 0, Skipped: 0
```

## Step 1: Configure Purpose-Specific OpenAI Settings

Edit `src/main/resources/application-openai.yml`. Keep the cloud profile explicit and keep the
embedding/vector path local:

```yaml
ai:
  providers:
    llm-provider: ${AI_LLM_PROVIDER:openai}
    embedding-provider: ${AI_EMBEDDING_PROVIDER:onnx}
    enable-fallback: false
    orchestration:
      llm-provider: ${AI_ORCHESTRATION_PROVIDER:openai}
      model: ${AI_ORCHESTRATION_MODEL:${OPENAI_MODEL:gpt-4o-mini}}
      temperature: 0.1
      max-tokens: ${AI_ORCHESTRATION_MAX_TOKENS:400}
    generation:
      llm-provider: ${AI_GENERATION_PROVIDER:openai}
      model: ${AI_GENERATION_MODEL:${OPENAI_MODEL:gpt-4o-mini}}
      temperature: 0.3
      max-tokens: ${AI_GENERATION_MAX_TOKENS:600}
    openai:
      enabled: ${OPENAI_ENABLED:true}
      api-key: ${OPENAI_API_KEY:}
      base-url: ${OPENAI_BASE_URL:https://api.openai.com/v1}
      model: ${OPENAI_MODEL:gpt-4o-mini}
      timeout: ${OPENAI_TIMEOUT_SECONDS:30}
    onnx:
      model-path: ${AI_FABRIC_ONNX_MODEL_PATH:./models/embeddings/all-MiniLM-L6-v2.onnx}
      tokenizer-path: ${AI_FABRIC_ONNX_TOKENIZER_PATH:./models/embeddings/tokenizer.json}
  vector-db:
    type: lucene
```

Why both a global provider and purpose blocks? The global value remains the default for calls that
use `LlmPurpose.DEFAULT`. The purpose blocks override only orchestration or generation. A blank field
inside a purpose block falls back to the corresponding provider default; it does not invent a new
provider implementation.

Do not put a real key in this file. `${OPENAI_API_KEY:}` is a reference with an empty default, not a
credential.

## Step 2: Make The Test Routes Unambiguous

Edit `src/test/resources/application-test.yml`:

```yaml
ai:
  providers:
    llm-provider: course-generation-test
    embedding-provider: course-test
    enable-fallback: false
    orchestration:
      llm-provider: course-orchestration-test
      model: course-test-orchestration
      temperature: 0.0
      max-tokens: 200
    generation:
      llm-provider: course-generation-test
      model: course-test-generation
      temperature: 0.2
      max-tokens: 600
```

These names must remain visibly test-only. A fixture called `openai` would make deterministic test
evidence look like a provider call that never happened.

## Step 3: Record Provider And Model Selection

Refactor `CourseTestAIConfiguration` so the test context has:

```text
CourseTestGenerationProvider
  shared recording/control fixture

CoursePurposeProvider("course-orchestration-test")
  delegates to the fixture

CoursePurposeProvider("course-generation-test")
  delegates to the fixture
```

The fixture records at least:

- selected provider name;
- request model;
- prompt/messages needed by existing tests;
- call count;
- a deliberate `failNext` switch.

Do not branch on support-message words to choose a response. Tests may inject a structured response,
but the application must still exercise AI Fabric's real provider manager, orchestration pipeline,
action registry, session store, and security boundaries.

## Step 4: Add The Purpose-Routing Contract

Create `PurposeRoutingIntegrationTest`. Call `AICoreService` twice with a request that leaves model
unset:

```java
aiCoreService.generateContent(request, LlmPurpose.ORCHESTRATION);
assertThat(recordingProvider.lastProvider()).isEqualTo("course-orchestration-test");
assertThat(recordingProvider.lastModel()).isEqualTo("course-test-orchestration");

aiCoreService.generateContent(request, LlmPurpose.GENERATION);
assertThat(recordingProvider.lastProvider()).isEqualTo("course-generation-test");
assertThat(recordingProvider.lastModel()).isEqualTo("course-test-generation");
```

Then fail the generation provider deliberately:

```java
recordingProvider.failNext();

assertThatThrownBy(() ->
    aiCoreService.generateContent(request, LlmPurpose.GENERATION))
    .hasMessageContaining("Failed to generate AI content");

assertThat(recordingProvider.lastProvider()).isEqualTo("course-generation-test");
assertThat(recordingProvider.generationCalls()).isOne();
```

One call proves that disabled fallback did not try the orchestration provider after generation
failed.

## Step 5: Report Effective Provider Posture

Inject `AIProviderConfig` into `CourseDeploymentInfoService` and resolve:

```java
AIProviderConfig.GenerationDefaults orchestration =
    providerConfig.resolveOrchestrationLlmDefaults();
AIProviderConfig.GenerationDefaults generation =
    providerConfig.resolveGenerationLlmDefaults();
```

Expose safe fields in `/api/demo/health`:

```json
{
  "provider": {
    "mode": "live-openai",
    "generationEnabled": true,
    "orchestration": "openai",
    "orchestrationModel": "gpt-4o-mini",
    "generation": "openai",
    "generationModel": "gpt-4o-mini",
    "embedding": "onnx",
    "vector": "lucene",
    "fallbackEnabled": false
  }
}
```

The payload must not include API keys, bearer tokens, full prompts, user messages, or provider
response bodies. Provider/model names are operational posture; secrets and user content are not.

When generation is disabled in the `local` profile, report both LLM purpose routes as `disabled`.
Do not report configured OpenAI defaults as active runtime calls.

## Step 6: Run The Keyless Gate

Run every test normally:

```bash
./mvnw --batch-mode --no-transfer-progress clean verify
```

Expected result at the solution checkpoint:

```text
Tests run: 42, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

Inspect the targeted test if you need a shorter feedback loop:

```bash
./mvnw --batch-mode --no-transfer-progress \
  -Dtest=PurposeRoutingIntegrationTest,CourseDeploymentInfoServiceTest test
```

That focused command is a development aid. The full `clean verify` remains the completion gate.

## Step 7: Run The Packaged Local Application

Download the real ONNX model, then execute the already-tested JAR:

```bash
./scripts/download-onnx-model.sh
COURSE_SMOKE_USE_EXISTING_JAR=true ./scripts/smoke-packaged.sh
jq . target/course-release-evidence/packaged-smoke-summary.json
```

Expected summary fields include:

```json
{
  "status": "PASS",
  "checkpoint": "course-0.3.3-p01-provider-routing",
  "profile": "local",
  "deployment": {
    "provider": {
      "generationEnabled": false,
      "orchestration": "disabled",
      "generation": "disabled",
      "embedding": "onnx",
      "vector": "lucene",
      "fallbackEnabled": false
    }
  }
}
```

The smoke proves the packaged application, ONNX, Lucene, HTTP wiring, tenant/security behavior, and
provider posture. It does not prove OpenAI.

## Step 8: Optional Real OpenAI Exercise

This section is optional for learner completion. Run it only after the keyless gate passes.

Set secrets in your terminal process:

```bash
export OPENAI_ENABLED=true
export OPENAI_API_KEY="<set locally; never commit>"
export OPENAI_MODEL="gpt-4o-mini"
export AI_ORCHESTRATION_MODEL="gpt-4o-mini"
export AI_GENERATION_MODEL="gpt-4o-mini"
./mvnw spring-boot:run -Dspring-boot.run.profiles=openai
```

Other safe places for the same variables:

| Environment | Where to set them |
| --- | --- |
| IDE | private run-configuration environment fields |
| Docker | `docker run -e OPENAI_API_KEY ...`, supplied by the caller |
| GitHub Actions | `${{ secrets.OPENAI_API_KEY }}` in a separately named keyed job |
| deployment | encrypted secret/environment UI on the deployment platform |

Do not paste a key into the course website, an HTTP request file, a committed `.env`, a screenshot,
or a support discussion.

In another terminal:

```bash
export COURSE_TOKEN=course-alex-local-token

curl -s http://localhost:8080/api/demo/health | jq .provider
curl -s -X POST http://localhost:8080/api/demo/reset
curl -s -X POST http://localhost:8080/api/demo/seed
curl -s -X POST http://localhost:8080/api/demo/index

curl -s -X POST http://localhost:8080/api/assistant/query \
  -H "Authorization: Bearer $COURSE_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"message":"What should I do if failed sign-ins locked me out?"}' | jq .

curl -s -X POST http://localhost:8080/api/assistant/orchestrate \
  -H "Authorization: Bearer $COURSE_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"message":"Why is ticket T-1001 unresolved?","conversationId":"course-provider-routing"}' | jq .
```

The grounded query exercises `GENERATION`. The orchestration request exercises `ORCHESTRATION`.
The health payload identifies the configured provider/model posture, while application logs and
responses remain free of the key.

## Intentional Live Failure

Use a separate test shell or local run configuration. Do not replace a deployment secret merely to
perform this exercise.

```bash
OPENAI_ENABLED=true \
OPENAI_API_KEY=invalid-course-check \
AI_ORCHESTRATION_MODEL=gpt-4o-mini \
AI_GENERATION_MODEL=gpt-4o-mini \
./mvnw spring-boot:run -Dspring-boot.run.profiles=openai
```

After seed/index, call `/api/assistant/query`. Expected behavior:

```text
provider/authentication failure is visible
no deterministic answer is returned
no write action executes
no other LLM provider is tried
the key value is absent from response and logs
```

Startup failure is also acceptable when provider validation rejects the missing/invalid
configuration before a request. A successful local-looking answer is not acceptable.

## Files Changed In The Solution

```text
src/main/resources/application-openai.yml
src/test/resources/application-test.yml
src/main/java/.../demo/CourseDeploymentInfoService.java
src/main/java/.../demo/CourseReadinessService.java
src/test/java/.../assistant/PurposeRoutingIntegrationTest.java
src/test/java/.../testsupport/CourseTestAIConfiguration.java
src/test/java/.../demo/CourseDeploymentInfoServiceTest.java
src/test/java/.../web/CourseApiTest.java
scripts/smoke-packaged.sh
requests/07-provider-routing.http
README.md
```

## Ownership And Security Boundaries

- The application chooses which purpose a call represents; untrusted request text does not select a
  provider, endpoint, or API key.
- AI Fabric resolves provider/model defaults and calls the configured provider.
- Spring AI supplies supported provider clients underneath the AI Fabric adapter.
- ONNX produces embeddings; it does not generate support answers.
- Lucene stores derived semantic evidence; it is not the support database.
- Health exposes effective posture, not credentials or request content.
- Prompt wording does not authorize an action or change tenant identity.

## Reset And Cleanup

Stop the application, clear runtime variables, and remove local generated evidence when needed:

```bash
unset OPENAI_ENABLED OPENAI_API_KEY OPENAI_MODEL
unset AI_ORCHESTRATION_PROVIDER AI_ORCHESTRATION_MODEL AI_ORCHESTRATION_MAX_TOKENS
unset AI_GENERATION_PROVIDER AI_GENERATION_MODEL AI_GENERATION_MAX_TOKENS
rm -rf data/lucene-knowledge-384 target/course-release-evidence
```

To restart the lesson from its immutable starter:

```bash
./scripts/reset-course.sh course-0.3.3-06-tested-solution
```

The reset script refuses to run with a dirty worktree. Commit useful work or move experiments before
resetting.

## Troubleshooting

| Symptom | Check | Expected correction |
| --- | --- | --- |
| Both test calls use one provider | `application-test.yml` purpose provider names | Configure distinct recording providers |
| Correct provider, wrong model | Request sets a model explicitly | Leave model unset when testing configured defaults |
| Health says OpenAI in local mode | health uses configured defaults despite generation disabled | Report LLM purposes as `disabled` in local runtime |
| OpenAI profile has no provider bean | `OPENAI_ENABLED`, key, dependency, profile | Enable only the intended profile and supply the secret privately |
| Invalid key returns a useful answer | fallback or app-side canned response exists | Disable fallback and remove disguised success behavior |
| ONNX cannot load | model/tokenizer paths | Run the download script or set both local paths |
| Key appears in output | logging/config projection | stop, rotate the key, remove the leak, and add a regression test |

## Done When

- [ ] `application-openai.yml` has separate orchestration and generation blocks.
- [ ] ONNX remains the embedding provider and Lucene remains the local vector provider.
- [ ] no secret exists in source, HTTP files, logs, reports, or health JSON.
- [ ] `PurposeRoutingIntegrationTest` proves both provider/model routes.
- [ ] a failed generation call makes one provider call and remains visible.
- [ ] health reports orchestration, generation, embedding, vector, and fallback posture.
- [ ] `./mvnw --batch-mode --no-transfer-progress clean verify` passes all 42 tests.
- [ ] the packaged local smoke passes and reports checkpoint `course-0.3.3-p01-provider-routing`.
- [ ] any OpenAI run is recorded separately as optional keyed evidence.

## Next Lesson

`PROD-02` will use the same application to separate an application position from an AI Fabric mode,
then prove that only server-allowlisted orchestration policy can enable retrieval or actions.
