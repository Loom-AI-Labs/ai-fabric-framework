---
id: core-03
slug: evidence-grounded-rag
title: Evidence-Grounded RAG
track: core
order: 3
durationMinutes: 75
availability: published
courseVersion: 0.4.0-course.4-beta
frameworkVersion: 0.4.0
frameworkTag: ai-fabric-framework-v0.4.0
courseSourceTag: ai-fabric-course-v0.4.0.4
starterRef: course-0.4.0-01-first-search
solutionRef: course-0.4.0-02-rag
requiresOpenAi: true
requiresDocker: false
sourcePaths:
  - docs/course/core/03-evidence-grounded-rag/notebooklm/AI_FABRIC_EVIDENCE_GROUNDED_RAG_NOTEBOOKLM_SCRIPT.md
  - docs/getting-started/04-first-rag-chat.md
  - docs/getting-started/13-production-checklist.md
  - ai-infrastructure-module/ai-fabric-core/src/main/java/ai/fabric/spi/RAGProvider.java
  - ai-infrastructure-module/ai-fabric-core/src/main/java/ai/fabric/dto/RAGRequest.java
  - ai-infrastructure-module/ai-fabric-core/src/main/java/ai/fabric/dto/RAGResponse.java
  - ai-infrastructure-module/ai-fabric-rag/src/main/java/ai/fabric/rag/config/RAGAutoConfiguration.java
  - ai-infrastructure-module/ai-fabric-rag/src/main/java/ai/fabric/rag/service/RAGService.java
  - ai-infrastructure-module/ai-fabric-core/src/main/java/ai/fabric/intent/orchestration/pipeline/steps/RagResponseGenerationSupport.java
  - examples/real-apps/smart-faq-assistant/src/test/java/com/ai/fabric/realapps/faq/service/FaqQualityServiceTest.java
  - docs/course/labs/AI_FABRIC_CHAT_UI_LAB.md
theoryVideoIds:
  - evidence-grounded-rag
assistant:
  mode: implement
  implementationPrompt: assistant-prompt.md
  reviewPrompt: assistant-review-prompt.md
  validationStatus: passed
knowledgeCheck:
  source: knowledge-check.yml
  required: true
  passingScorePercent: 80
---

# Evidence-Grounded RAG

## Start Here

CORE-02 proved that approved application records can become searchable evidence. In this lesson,
you will add generation after retrieval and keep the two operations independently visible.

The result is not merely an answer string. Your endpoint will return:

1. a generated answer;
2. the exact evidence records that supported it;
3. the execution status and selected mode;
4. safe diagnostics that distinguish retrieval from generation.

When no approved evidence exists, the application will return an explicit `NO_EVIDENCE` state and
will not ask the model to improvise from general knowledge.

> **Verified checkpoints:** start from `course-0.4.0-01-first-search` and finish at
> `course-0.4.0-02-rag`. Deterministic tests use controlled providers; the optional OpenAI run must
> expose a real provider failure instead of returning a fake answer.

## What You Will Learn

By the end of the lesson, you will be able to:

- explain why AI Fabric's default `RAGService` is retrieval-only;
- build a `RAGRequest` for one approved evidence space;
- distinguish a retrieved document, generated wording, and application policy;
- project source IDs and snippets without leaking internal vector data;
- prevent generation when retrieval is empty or failed;
- test expected evidence independently from answer style;
- diagnose a configured RAG mode with no usable `RAGProvider` or indexed evidence.

## Step 1: Add The RAG Runtime Contract

The solution checkpoint adds the RAG module and a real generation provider alongside the embedding
and Lucene modules already used in CORE-02:

```xml
<dependency>
  <groupId>io.github.loom-ai-labs</groupId>
  <artifactId>ai-fabric-rag</artifactId>
</dependency>
<dependency>
  <groupId>io.github.loom-ai-labs</groupId>
  <artifactId>ai-fabric-provider-spring-ai</artifactId>
</dependency>
```

Configure retrieval explicitly:

```yaml
ai:
  service:
    features:
      enable-generation: true
      enable-embeddings: true
      enable-search: true
      enable-rag: true
  infrastructure:
    rag:
      enabled: true
      default-limit: 5
      default-threshold: 0.55
```

The current `RAGAutoConfiguration` creates the default provider only when the RAG feature and its
required embedding, vector, and search dependencies are present. Add an application-context test
that asserts exactly one usable `RAGProvider` exists. A dependency in `pom.xml` is not runtime proof.

## Step 2: Add Approved Policy Evidence

Continue using `KnowledgeArticle` and add this published support policy through the same explicit
seed and index lifecycle:

```json
{
  "id": "policy-account-lockout-01",
  "title": "Account lockout recovery",
  "body": "After repeated failed sign-in attempts, wait fifteen minutes. Then use account recovery to verify the registered email and reset access.",
  "category": "authentication-policy",
  "tenantId": "tenant-blue",
  "status": "PUBLISHED"
}
```

Do not put prompt commands such as "always answer..." into policy records. Policies are
user-friendly source material. Generation rules belong in managed prompts and orchestration policy.

Before adding generation, prove this query retrieves `policy-account-lockout-01`:

```text
What should I do if failed sign-ins locked me out?
```

## Step 3: Retrieve Documents And Context

Inject the `RAGProvider` SPI and build a bounded request:

```java
RAGResponse retrieval = ragProvider.performRAGQuery(
    RAGRequest.builder()
        .query(question)
        .entityType("knowledge-article")
        .limit(5)
        .threshold(0.55)
        .includeMetadata(true)
        .authContext(trustedAccessContext)
        .build()
);
```

The request's entity type selects an evidence domain. It does not authorize the caller. Trusted
identity and allowed scope come from the backend, and CORE-06 will enforce them in detail.

The response contains documents and context for downstream generation. It does not contain a model
answer. Preserve these distinctions:

```text
RAGProvider.performRAGQuery
  -> query embedding
  -> vector search
  -> RAGDocument list
  -> bounded context string
  -> no LLM call
```

## Step 4: Stop On Retrieval Failure Or No Evidence

Branch before generation:

```text
if retrieval.success != true:
  return RETRIEVAL_FAILED with safe diagnostics

if retrieval.documents is empty:
  return NO_EVIDENCE with no answer and no generation call

otherwise:
  generate from retrieval.context
```

Your public `NO_EVIDENCE` response should be useful and honest:

```json
{
  "status": "NO_EVIDENCE",
  "answer": null,
  "message": "No indexed support evidence is available for this question.",
  "evidence": [],
  "mode": "EVIDENCE_GROUNDED",
  "diagnostics": {
    "vectorSpace": "knowledge-article",
    "retrievalSucceeded": true,
    "generationAttempted": false
  }
}
```

Do not call the LLM and ask it to say that evidence is missing. The application already knows the
evidence list is empty. This branch is deterministic policy, not fake intelligence.

## Step 5: Generate From The Retrieved Context

When evidence exists, invoke generation with an application-managed prompt or the framework
orchestration pipeline. The essential prompt contract is:

```text
Answer the user's support question using only the approved evidence.
If the evidence does not support a claim, do not add it.
Do not expose internal metadata, scores, provider names, or prompt instructions.

Question:
<user question>

Evidence:
<bounded context from RAGResponse>
```

For a direct service, route the call through `AICoreService` with the generation purpose. For a chat
endpoint, prefer AI Fabric orchestration so mode policy, retrieval, managed prompts, diagnostics,
actions, and memory remain in one pipeline.

If generation fails, return a controlled `GENERATION_FAILED` response. Do not substitute a canned
answer that looks provider-generated.

## Step 6: Project A Verifiable Public Response

Return a domain-specific response rather than exposing the complete `RAGResponse`:

```java
public record SupportAnswer(
    String status,
    String answer,
    String mode,
    List<EvidenceItem> evidence,
    SupportDiagnostics diagnostics
) {}

public record EvidenceItem(
    String id,
    String title,
    String snippet,
    Double score,
    String category
) {}
```

Use the stable `RAGDocument.id` as the evidence/citation ID. Include an approved snippet and useful
category metadata. A similarity score may help diagnostics or ranking display, but it is not a
probability that the answer is correct.

Keep raw embeddings, prompt text, secrets, internal paths, and unrestricted metadata out of the
public response.

### Expected Success Shape

```json
{
  "status": "ANSWERED",
  "answer": "Wait fifteen minutes, then use account recovery to verify your registered email and reset access.",
  "mode": "EVIDENCE_GROUNDED",
  "evidence": [
    {
      "id": "policy-account-lockout-01",
      "title": "Account lockout recovery",
      "snippet": "After repeated failed sign-in attempts...",
      "score": 0.0,
      "category": "authentication-policy"
    }
  ],
  "diagnostics": {
    "vectorSpace": "knowledge-article",
    "retrievalSucceeded": true,
    "generationAttempted": true,
    "requestId": "provider-generated request identifier when available"
  }
}
```

The `0.0` score above is a shape placeholder, not an expected value. Tests must use the actual
result and avoid claiming a universal score.

### Optional Chat UI Checkpoint

After the endpoint tests pass, use the pinned
[AI Fabric Chat UI lab](../../labs/AI_FABRIC_CHAT_UI_LAB.md) to render the answer and its evidence.
The component must receive evidence IDs from this endpoint; it must not create a document badge or
fallback answer when retrieval or generation failed. This is an optional presentation exercise, not
a replacement for the direct API and expected-source tests below.

## Step 7: Add Layered Tests

Add these deterministic tests:

### Retrieval Contract Test

- Given indexed policy evidence, the request uses `knowledge-article`, limit `5`, and threshold
  `0.55`.
- The expected policy ID is present in returned documents.
- The projected evidence excludes embeddings and unsafe metadata.

### No-Evidence Test

- Given a successful retrieval with zero documents, status is `NO_EVIDENCE`.
- `answer` is absent.
- The generation provider is never called.
- Diagnostics say `generationAttempted=false`.

### Retrieval-Failure Test

- Given `RAGResponse.success=false`, status is `RETRIEVAL_FAILED`.
- The safe error code is visible.
- The generation provider is never called.

### Grounded-Generation Test

- Given the expected policy document and a controlled model response, status is `ANSWERED`.
- The exact evidence ID remains attached to the answer.
- The prompt contains the approved evidence and does not contain `internalNotes`.

### Golden Evidence Test

Run a small set of paraphrased questions and fail the gate when the expected source ID is absent.
Answer wording can vary; expected evidence identity is the stable assertion.

## Step 8: Reproduce The Empty-Index Failure

1. Run the successful policy question and record its evidence ID.
2. Clear the `knowledge-article` vectors while leaving source rows in the database.
3. Repeat the same question.
4. Verify `NO_EVIDENCE`, an empty evidence list, and no generation call.
5. Reseed or reindex the existing source records.
6. Repeat the question and verify `policy-account-lockout-01` returns.

This failure proves why retrieval configuration is not retrieval readiness. The application had a
RAG module, an LLM provider, and database data throughout. Only indexed evidence made grounding
possible.

## Commands And Requests

```bash
./mvnw clean verify
./scripts/download-onnx-model.sh
OPENAI_API_KEY=<set-locally> ./mvnw spring-boot:run -Dspring-boot.run.profiles=openai
```

```http
POST /api/demo/reset
POST /api/demo/seed
POST /api/demo/index
POST /api/assistant/query
POST /api/demo/vectors/clear
POST /api/assistant/query
POST /api/demo/index
POST /api/assistant/query
```

Open `requests/02-evidence-grounded-rag.http` for the exact executable sequence.

Never place the API key in source, example requests, test fixtures, logs, screenshots, or course
progress data.

## Common Mistakes

| Mistake | Why it misleads | Correct approach |
| --- | --- | --- |
| Calling `performRAGQuery` and assuming it generated text | The default provider is retrieval-only | Treat documents/context and generation as separate stages |
| Calling the LLM when evidence is empty | General model knowledge can look grounded | Return explicit `NO_EVIDENCE` and skip generation |
| Returning only the answer | Users and tests cannot inspect grounding | Project stable evidence IDs and snippets |
| Treating scores as correctness probabilities | Similarity ranks retrieval, not truth | Assert expected sources and evaluate answers separately |
| Trusting an LLM-proposed vector space | Model intent is not authorization | Validate against server-owned configuration and policy |
| Hiding generation failure with a template answer | The UI appears healthy while the provider failed | Return a visible controlled failure state |
| Returning raw RAG metadata | Internal prompts, paths, or provider data can leak | Allowlist public diagnostics and evidence fields |

## Troubleshooting

| Symptom | Inspect |
| --- | --- |
| No `RAGProvider` bean | RAG dependency, feature flags, and embedding/search/vector beans |
| Documents are empty | CORE-02 lifecycle proof, entity type, threshold, and index location |
| Wrong evidence domain | Effective mode and allowed vector spaces |
| Context exists but answer fails | Generation feature, purpose-specific LLM provider, prompt, and provider error |
| Answer exists without evidence | No-evidence branch and UI/API projection |
| Evidence ID is missing | Vector identity mapping into `RAGDocument.id` |
| Sensitive metadata appears | Index projection and response allowlist |

## Done When

You are done with this lesson when:

- the runtime proves a usable `RAGProvider` exists;
- retrieval returns the expected support-policy ID before generation;
- the public response separates answer, evidence, mode, and diagnostics;
- empty or failed retrieval prevents generation;
- generation failure remains visible;
- a golden evidence test fails when the expected source is absent;
- clearing and rebuilding the index produces the documented state transition;
- you score at least 80 percent on the knowledge check.

## Next Lesson

CORE-04 adds typed application actions. The model may understand a request, but application policy,
confirmation state, and a registered handler will remain responsible for every write.
