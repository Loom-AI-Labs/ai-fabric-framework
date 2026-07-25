---
id: prod-06
slug: rag-quality
title: RAG Quality And Prompt Regression
track: production
order: 6
durationMinutes: 75
availability: preview
courseVersion: 0.4.0-course.4-beta
frameworkVersion: 0.4.0
frameworkTag: ai-fabric-framework-v0.4.0
courseSourceTag: ai-fabric-course-v0.4.0.4
starterRef: course-0.4.0-p05-live-data-sync
solutionRef: course-0.4.0-p06-rag-quality
requiresOpenAi: false
requiresDocker: false
optionalProviderExercises:
  - openai
sourcePaths:
  - docs/course/production/06-rag-quality/notebooklm/AI_FABRIC_RAG_QUALITY_PROMPT_REGRESSION_NOTEBOOKLM_SCRIPT.md
  - ai-infrastructure-module/ai-fabric-rag/src/main/java/ai/fabric/rag/evaluation/springai/SpringAiRagEvaluationService.java
  - ai-infrastructure-module/ai-fabric-core/src/main/java/ai/fabric/prompt/PromptTemplateResolver.java
theoryVideoIds:
  - rag-quality-prompt-regression
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
  status: published
  generator: NotebookLM
  purpose: pre-lesson-theory
  placement: before-lab
  targetDurationMinutes: 10
  title: Measuring RAG Quality Before Generation
  publicUrl: https://www.youtube.com/watch?v=bSyMDQORJOY
  transcript: notebooklm/AI_FABRIC_RAG_QUALITY_PROMPT_REGRESSION_NOTEBOOKLM_SCRIPT.md
  sourceManifest: notebooklm/source-manifest.yml
---

# RAG Quality And Prompt Regression

You already have evidence-grounded RAG. This lesson makes its important behavior measurable before
you ask a model to phrase an answer.

A convincing answer can still be wrong because retrieval selected the wrong source, crossed a tenant
boundary, retained stale text, or had no evidence for the claim. Your release gate therefore starts
with deterministic evidence contracts. Live model output is a separate observation.

## Outcome

You will:

- define tenant-aware golden questions;
- assert expected and forbidden evidence IDs;
- detect missing and stale source fragments;
- distinguish an empty index from insufficient context;
- test prompt overlay structure without comparing generated prose;
- keep retrieval and generation failures visible;
- run the scorecard against real ONNX embeddings and Lucene in the packaged JAR;
- optionally observe a live OpenAI answer after the deterministic gate passes.

## Start Here

```bash
git clone https://github.com/Loom-AI-Labs/ai-fabric-course-support-assistant.git
cd ai-fabric-course-support-assistant
git fetch --tags
git show-ref --verify --quiet refs/tags/course-0.4.0-p05-live-data-sync \
  || { echo "The required 0.4 starter checkpoint could not be resolved."; exit 1; }
git switch --detach course-0.4.0-p05-live-data-sync
./mvnw --batch-mode --no-transfer-progress clean verify
```

No provider key is required for the lesson.

## Quality Boundary

```text
application source rows
  -> AI Fabric projection and vector evidence
  -> authenticated tenant filter
  -> retrieved evidence IDs and content
  -> deterministic quality decision
  -> optional LLM answer observation
```

The first five steps are release-blocking. The last step is useful evidence about one model and one
configuration, but it cannot repair a failed retrieval case.

## Step 1: Define Golden Questions

`RagQualityCatalog` keeps application-owned expectations. Tenant Blue cases include account lockout,
billing method, and VPN recovery. Tenant Red has its own VPN case.

Each case declares:

- a stable case ID;
- a natural user question;
- required evidence IDs;
- forbidden evidence IDs;
- fragments that prove current source content;
- fragments that must not appear.

Do not use an expected answer paragraph. Exact prose changes across models and prompt revisions. A
stable source ID and current source fragment are stronger contracts.

## Step 2: Evaluate Evidence, Not Eloquence

`RagQualityService` calls the same tenant-filtered `KnowledgeEvidenceService` used by the app. It
returns a structured result with observed IDs, missing IDs, forbidden IDs, missing fragments, stale
fragments, and failure codes.

```bash
curl -s http://localhost:8080/api/quality/rag/golden \
  -H 'Authorization: Bearer course-alex-local-token' | jq
```

Expected fields:

```json
{
  "suiteId": "support-knowledge-golden-v1",
  "passed": true,
  "totalCases": 3,
  "failedCases": 0
}
```

Run the same endpoint with Riley's token. The result must contain `article-vpn-red` and must not
contain `article-vpn-blue` or `article-payroll-red-restricted`.

The request has no tenant field. Identity and tenant still come from the verified backend principal.

## Step 3: Distinguish No Source From Insufficient Context

An empty index is an expected lifecycle state:

```bash
curl -s -X POST http://localhost:8080/api/demo/vectors/clear
curl -s -X POST http://localhost:8080/api/quality/rag/evaluate \
  -H 'Authorization: Bearer course-alex-local-token' \
  -H 'Content-Type: application/json' \
  -d '{
    "caseId":"empty-index",
    "question":"How do I recover access?",
    "expectNoEvidence":true
  }' | jq
```

This passes only when no evidence is returned.

After restoring the index, ask for a source the corpus does not contain:

```bash
curl -s -X POST http://localhost:8080/api/demo/index
curl -s -X POST http://localhost:8080/api/quality/rag/evaluate \
  -H 'Authorization: Bearer course-alex-local-token' \
  -H 'Content-Type: application/json' \
  -d '{
    "caseId":"missing-retention-policy",
    "question":"How long are audit logs retained?",
    "expectedEvidenceIds":["article-audit-retention"]
  }' | jq
```

The HTTP request succeeds because evaluation ran, while the result has `passed=false` and
`EXPECTED_EVIDENCE_MISSING`. Do not convert that result into generic model advice.

## Step 4: Detect Stale Evidence

Update the billing article through the trusted Data Sync flow, then evaluate:

```bash
curl -s -X POST http://localhost:8080/api/quality/rag/evaluate \
  -H 'Authorization: Bearer course-alex-local-token' \
  -H 'Content-Type: application/json' \
  -d '{
    "caseId":"fresh-billing-copy",
    "question":"Where can I download my invoice?",
    "expectedEvidenceIds":["article-billing-method"],
    "requiredContentFragments":["download the invoice"],
    "forbiddenContentFragments":["replacement method"]
  }' | jq
```

The case fails with `REQUIRED_CONTENT_MISSING` when the new source did not reach the vector store and
with `STALE_CONTENT_RETURNED` when old evidence remains retrievable.

## Step 5: Gate Prompt Resources Structurally

Prompt regressions should prove resolution and required slots without freezing model wording:

```bash
curl -s http://localhost:8080/api/quality/prompts \
  -H 'Authorization: Bearer course-alex-local-token' | jq
```

The contract checks:

- support answer resolves from `v1-course-support`;
- an omitted action-selection prompt falls back to base `v1`;
- the answer template retains `{{query}}` and `{{context}}` slots;
- no prompt body is returned by the diagnostics endpoint.

Prompts guide model behavior. Tenant filters, access policy, typed action schemas, and confirmation
remain the enforcement layer.

## Step 6: Keep Failures Visible

`SupportAssistantServiceTest` proves a failed retrieval returns `RETRIEVAL_FAILED`, skips generation,
and does not expose adapter details. Invalid citations or provider errors return
`GENERATION_FAILED` with no canned answer.

The packaged `local` profile deliberately has generation disabled. Calling `/api/assistant/query`
after indexing returns HTTP `503` and `LLM_GENERATION_FAILED`; the application does not silently
substitute a deterministic sentence.

## Step 7: Run The Required Gate

```bash
./mvnw --batch-mode --no-transfer-progress clean verify
./scripts/download-onnx-model.sh
COURSE_SMOKE_USE_EXISTING_JAR=true ./scripts/smoke-packaged.sh
jq '.ragQuality' target/course-release-evidence/packaged-smoke-summary.json
```

Published checkpoint: `course-0.4.0-p06-rag-quality`. Its verification gate passed both tenant
suites, insufficient-context, stale-source, empty-index, prompt-structure, and disabled-generation
cases with real ONNX/Lucene.

## Optional OpenAI Observation

Set the key only in your shell, IDE secret field, CI secret, or deployment secret store:

```bash
export OPENAI_ENABLED=true
export OPENAI_API_KEY='<set locally; never commit>'
export OPENAI_MODEL='gpt-4o-mini'
./mvnw spring-boot:run -Dspring-boot.run.profiles=openai
```

Run the deterministic golden endpoint first. Then call `/api/assistant/query` and retain provider,
model, purpose, status, usage, and citation IDs. Do not assert exact answer prose. If the key is
missing or invalid, the live call must fail visibly; it must not return the local test fixture or a
canned answer.

## Intentional Failures

1. Expect `article-audit-retention`. The case returns `passed=false` and
   `EXPECTED_EVIDENCE_MISSING`.
2. Add `article-vpn-red` as required for Alex. The case fails; the app must not widen the tenant
   filter to satisfy the test.
3. Require old billing text after an update. The freshness contract fails.
4. Start the OpenAI profile without a valid key and invoke generation. The provider failure remains
   visible.

## Done When

- both tenant golden suites pass with expected and forbidden IDs;
- empty index and insufficient context are represented separately;
- stale source text is rejected after Data Sync update;
- prompt resolution and required slots pass without an exact answer assertion;
- retrieval and generation failures have no hidden fallback;
- 63 tests and the packaged ONNX/Lucene scorecard pass;
- optional OpenAI evidence is labelled `PASS`, `FAIL`, or `NOT RUN` independently.

## Reset

```bash
./scripts/reset-course.sh
git switch --detach course-0.4.0-p05-live-data-sync
```

## Troubleshooting

**A golden case misses its ID:** inspect the indexed count, tenant metadata, threshold, source
projection, and actual observed IDs. Do not lower policy boundaries merely to make the case pass.

**A case returns extra same-tenant documents:** extra candidates are allowed unless the case names
them as forbidden. Add a forbidden ID only when its presence would make the supplied context unsafe
or materially wrong.

**HTTP is 200 while `passed=false`:** evaluation completed successfully and found a quality failure.
CI must gate on the result field, not only the HTTP status.

**Live wording changes:** expected. Assert citations and evidence facts. Treat generated text as an
observation unless a structured response contract provides stable fields.

## Next Lesson

PROD-07 replaces Lucene with local Docker Qdrant while preserving this exact retrieval and quality
contract.
