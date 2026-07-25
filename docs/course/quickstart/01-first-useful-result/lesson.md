---
id: qs-01
slug: first-useful-result
title: First Useful Result
track: quickstart
order: 1
durationMinutes: 75
availability: published
courseVersion: 0.3.3-course.2-beta
frameworkVersion: 0.3.3
frameworkTag: ai-fabric-framework-v0.3.3
courseSourceTag: ai-fabric-course-v0.3.3.2
starterRef: course-0.3.3-00-starter
solutionRef: course-0.3.3-01-first-search
requiresOpenAi: false
requiresDocker: false
sourcePaths:
  - docs/getting-started/02-installation.md
  - docs/getting-started/03-first-semantic-search.md
  - docs/getting-started/08-local-onnx-embeddings.md
  - docs/getting-started/09-vector-storage-lucene.md
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

# First Useful Result

## Start Here

AI Fabric adds application-level AI capabilities to Spring Boot, including retrieval, governed
actions, memory, privacy, and provider orchestration. This quickstart demonstrates one small
capability before the Core track explains the complete architecture.

You will take five application-owned support articles, index their approved content through AI
Fabric, and retrieve the expected article with a differently worded question. The goal is a real,
inspectable result before a long architecture discussion.

You do not need an LLM, an OpenAI key, Docker, or a framework source checkout for this workflow.

> **Published lab.** Start from
> [`course-0.3.3-00-starter`](https://github.com/Loom-AI-Labs/ai-fabric-course-support-assistant/tree/course-0.3.3-00-starter)
> and use
> [`course-0.3.3-01-first-search`](https://github.com/Loom-AI-Labs/ai-fabric-course-support-assistant/tree/course-0.3.3-01-first-search)
> to review your result. Both refs are immutable learner-repository checkpoints validated from a
> clean checkout.

## What You Will Prove

By completing this quickstart, you will prove that:

- your Spring Boot application resolves AI Fabric from Maven Central;
- local ONNX embeddings and local Lucene vector storage start without cloud credentials;
- seeded database records return no vector evidence before indexing;
- an explicit AI Fabric indexing operation makes approved content retrievable;
- a paraphrased query returns the expected entity ID, score, content, and metadata;
- focused tests preserve both the no-index and indexed behaviors.

## Application Shape

The standalone course repository begins with this small support-knowledge application:

```text
pom.xml
src/main/java/.../SupportAssistantApplication.java
src/main/java/.../knowledge/KnowledgeArticle.java
src/main/java/.../knowledge/KnowledgeArticleService.java
src/main/java/.../web/KnowledgeSearchController.java
src/main/resources/application-local.yml
src/main/resources/ai-entity-config.yml
```

The entity will use AI Fabric's real annotation model:

```java
@AICapable(entityType = "knowledge-article")
class KnowledgeArticle {
    @AIIdentity
    @AIContext(
        key = "entityId",
        dataType = AIContextDataType.ID,
        priority = 100,
        required = true
    )
    private String id;

    @AISearchable
    private String title;

    @AISearchable
    private String body;

    @AIContext
    private String category;
}
```

For this quickstart, remember only this distinction:

- `@AISearchable` marks approved text that contributes to semantic retrieval.
- `@AIContext` preserves structured identity and metadata without embedding those values.

CORE-01 explains the complete AI Fabric ownership and module model. CORE-02 returns to this entity and
explains projection, embedding compatibility, metadata, indexing, update, delete, and backfill in
depth.

## Build Sequence

From the starter checkpoint, follow this sequence:

1. Verify Java 21 and check out the published starter ref.
2. Run the starter tests before editing anything.
3. Add the AI Fabric BOM and the smallest required module set.
4. Configure local ONNX embeddings and local Lucene storage.
5. Define approved searchable content and category metadata.
6. Seed five support articles without indexing them.
7. Search using a paraphrased account-access question.
8. Confirm that no vector evidence is returned.
9. Index the five records through AI Fabric.
10. Repeat the same query and inspect the returned evidence.
11. Run the complete focused test suite.

## Manual Path

Inspect every dependency, annotation, configuration property, service call, and test before adding it
to the starter project. Do not copy code from an unpinned branch or build the framework reactor.

## Coding-Assistant Path

Give your coding assistant the supplied QS-01 implementation prompt. It must inspect the pinned AI
Fabric APIs, preserve application ownership, reproduce the no-index result, and run the same tests as
the manual path. It must not copy the solution checkpoint or invent framework APIs.

The assistant prompt has been validated against the same starter and behavioral checks as the
manual path. You still own the diff review and must run the declared tests yourself.

## Commands

```bash
./mvnw clean verify
./scripts/download-onnx-model.sh
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

## Requests

```http
POST /api/demo/reset
POST /api/demo/seed
GET /api/demo/readiness
GET /api/knowledge/search?q=How+can+I+recover+access+to+my+account
POST /api/demo/index
GET /api/knowledge/search?q=How+can+I+recover+access+to+my+account
```

## Intentional Failure

Search after seeding but before indexing.

The correct result is:

- the HTTP request succeeds;
- no matching vector evidence is returned;
- no generic support answer is fabricated;
- readiness distinguishes five source records from zero indexed vectors.

Fix the state by running the explicit indexing operation. Do not add browser keyword matching, a
canned answer, or an LLM fallback.

## Expected Success Evidence

After indexing, repeat the exact same paraphrased query. Your proof must include:

- HTTP 200;
- the expected support article ID;
- a similarity score;
- approved support content;
- category and entity metadata;
- readiness showing five indexed articles.

A fluent answer without an evidence ID is not sufficient proof.

## Tests Required Before Publication

The executable checkpoint must prove:

- clean Maven Central dependency resolution;
- discovery of the selected embedding and vector providers;
- no evidence before indexing;
- the expected article ID after indexing;
- preservation of approved metadata;
- no remote embedding request from the local profile;
- successful startup from the packaged application.

## Troubleshooting

| What you observe | What you inspect first |
| --- | --- |
| No evidence after seed | Confirm the index operation ran and readiness reports vectors |
| ONNX startup failure | Confirm the configured model and tokenizer assets exist |
| Lucene dimension error | Confirm the selected embedding model and index use compatible dimensions |
| Results from unexpected data | Confirm the active vector space and Lucene path |
| Missing metadata | Inspect the application projection and indexing call |
| Useful answer without evidence | Stop and inspect for a hidden fallback or application-authored response |
| Cloud-key error | Confirm the local profile selects ONNX and disables live providers |

## Check Your Result

Before the knowledge check, make sure you can answer:

1. What changed between the first search and the second search?
2. Which response fields prove that application evidence was retrieved?
3. Why is an empty result before indexing correct?
4. Why would a generic model answer fail this quickstart?

## Done When

You are done when the app starts without cloud keys, the golden query returns
the expected evidence only after indexing, and all focused tests pass from a clean checkout.

## Reset And Cleanup

With the solution application running, use `./scripts/reset-course.sh` to remove and reseed only the
course fixtures and clear the lesson's local vector state.

## Next Lesson

In CORE-01, you will learn what AI Fabric is, why it was built, when to use it, how its modules fit
together, and where application ownership ends and framework orchestration begins.
