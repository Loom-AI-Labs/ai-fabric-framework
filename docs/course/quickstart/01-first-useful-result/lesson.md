---
id: qs-01
slug: first-useful-result
title: First Useful Result
track: quickstart
order: 1
durationMinutes: 75
availability: preview
courseVersion: 0.3.3-course.1-beta
frameworkVersion: 0.3.3
frameworkTag: ai-fabric-framework-v0.3.3
courseSourceTag: unreleased
starterRef: planned
solutionRef: planned
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
  validationStatus: planned
knowledgeCheck:
  source: knowledge-check.yml
  required: true
  passingScorePercent: 80
video:
  status: script-ready
  generator: notebooklm
  purpose: pre-lesson-theory
  placement: before-lab
  targetDurationMinutes: 7
  title: From Application Record To Semantic Evidence
  publicUrl: null
  transcript: notebooklm/07-video-script.md
  sourceManifest: notebooklm/source-manifest.yml
---

# First Useful Result

## What You Will Achieve

You will learn the smallest useful AI Fabric workflow: take application-owned support knowledge,
turn approved fields into vector evidence, and retrieve the expected article with a differently
worded question.

By the end of this lesson, you will be able to:

- explain why saving a database row does not make it semantically searchable;
- choose which fields become searchable content and which remain structured metadata;
- trace indexing and search through AI Fabric, ONNX, and Lucene;
- distinguish semantic retrieval from language-model generation;
- diagnose the honest no-evidence result that appears before indexing.

> **Current lesson status: Preview.** You can complete the theory and knowledge check now. The
> standalone starter project, immutable starter and solution checkpoints, and reviewed video must be
> published before you can run the practical lab from a clean checkout. Commands in this preview are
> clearly marked as planned and are not presented as executed proof.

## The Problem You Are Solving

Imagine that your Spring Boot application already stores a support article explaining how a customer
can recover account access. A user asks:

> How can I get back into my account?

The article may not contain those exact words. Keyword matching can miss it, and a generic language
model answer would not prove that your application knowledge was used. You need semantic retrieval:
the question and the approved article content are converted into compatible vectors, compared by
similarity, and returned with source identity.

This lesson proves retrieval only. You do not need an LLM or an OpenAI key.

## Build The Right Mental Model

Keep these three states separate:

1. **Domain record** - your application owns the support article and its lifecycle.
2. **Search projection** - your application decides which text and metadata AI Fabric may process.
3. **Vector evidence** - the configured embedding and vector providers make that projection
   retrievable.

A record can exist in your database while its vector evidence is missing, stale, or stored in a
different vector space. Database persistence and retrieval readiness are separate facts.

## Describe AI-Facing Data

AI Fabric gives you annotations and configuration for describing an AI-enabled entity. The following
shape is conceptual until the published starter checkpoint provides the exact compilable class:

```java
@AICapable(entityType = "knowledge-article")
class KnowledgeArticle {
    @AIContext(dataType = "id")
    private String id;

    @AISearchable
    private String title;

    @AISearchable
    private String body;

    @AIContext
    private String category;
}
```

Read the annotations as an ownership contract:

- `@AICapable` identifies the entity type and connects the class to AI Fabric configuration.
- `@AISearchable` marks approved text that is embedded and used for semantic search.
- `@AIContext` marks structured metadata that is stored with the vector but is not embedded.
- `ai-entity-config.yml` can override annotation defaults; configuration takes precedence over
  annotation defaults, which take precedence over framework defaults.

Annotations describe intended AI-facing behavior. They do not prove that create, update, delete, or
backfill operations have actually indexed anything.

## Trace Indexing End To End

When you index an article, the data moves through these boundaries:

```text
KnowledgeArticle record
  -> you select approved searchable fields and metadata
  -> AI Fabric builds the embedding request
  -> the ONNX provider creates a numeric vector locally
  -> AI Fabric writes vector + stable ID + content + metadata
  -> Lucene persists the searchable evidence
```

The stable entity ID connects the vector evidence back to your domain record. Searchable text gives
the vector its semantic meaning. Metadata preserves structured values such as category without
forcing those values into the embedding.

The embedding dimension is part of the provider contract. Indexed content and search queries must
use a compatible model and dimension; otherwise the vectors cannot be compared correctly.

## Trace A Search Request

When you submit a paraphrased support question, the request follows this path:

```text
Your API receives the question
  -> AI Fabric asks the configured provider for a query embedding
  -> ONNX creates the query vector with the compatible model
  -> Lucene performs similarity search
  -> AI Fabric returns IDs, scores, content, and metadata
  -> your API projects the evidence for the caller
```

Lucene returns the nearest matching vectors according to the search request. Treat the similarity
score as a ranking signal, not a calibrated probability or a guaranteed confidence percentage.
Result limits and thresholds shape what evidence is returned; they do not make absent evidence
appear.

## Understand What Each Layer Owns

| Boundary | What it owns |
| --- | --- |
| Your application | Article lifecycle, approved content, stable IDs, metadata, indexing triggers, API response |
| AI Fabric | Entity processing, embedding and vector contracts, indexing coordination, search request and response |
| ONNX provider | Local text-to-vector inference and embedding dimension |
| Lucene provider | Vector persistence, metadata storage, and similarity lookup |
| Browser or API client | Request input and result presentation, not retrieval intelligence |

This boundary matters when something fails. If the vector does not exist, changing the browser or
asking an LLM to invent an answer does not repair indexing.

## Distinguish Retrieval From RAG

Semantic retrieval returns evidence. RAG adds a later generation step that supplies retrieved
evidence to an LLM and asks it to compose an answer.

```text
QS-01: question -> semantic retrieval -> evidence
Later RAG lesson: question -> semantic retrieval -> evidence -> LLM -> grounded answer
```

Keeping the two stages separate lets you prove that retrieval works before generated wording can
hide missing or incorrect evidence.

## Practical Lab Preview

When the executable checkpoint is published, you will start with a Java 21 and Spring Boot 4.1.x
application containing:

- a Maven wrapper and AI Fabric `0.3.3` BOM import;
- local ONNX embeddings and Lucene vector storage;
- no cloud credentials;
- five support-article fixtures;
- explicit reset, seed, index, readiness, and search operations.

You will work with this shape:

```text
pom.xml
src/main/java/.../SupportAssistantApplication.java
src/main/java/.../knowledge/KnowledgeArticle.java
src/main/java/.../knowledge/KnowledgeArticleService.java
src/main/java/.../web/KnowledgeSearchController.java
src/main/resources/application-local.yml
src/main/resources/ai-entity-config.yml
```

The lab will ask you to:

1. Resolve AI Fabric from Maven Central using the published BOM.
2. Configure the required AI Fabric, ONNX, and Lucene modules.
3. Model approved searchable content and category metadata.
4. Seed five domain records without indexing them.
5. Search before indexing and preserve the explicit no-evidence result.
6. Index the records through AI Fabric.
7. Repeat the same paraphrased query.
8. Inspect the returned entity ID, score, content, metadata, and readiness count.

## Manual And Coding-Assistant Paths

You will be able to complete the same behavior contract in either of two ways.

### Manual Path

You inspect each dependency, annotation, configuration property, service call, and test before adding
it to the starter project.

### Coding-Assistant Path

You give your coding assistant the supplied implementation prompt. The assistant must inspect the
current framework APIs, preserve application ownership, reproduce the no-index state, and run the
same tests as the manual path. It must not copy the solution checkpoint or invent APIs.

The assistant prompt remains marked `planned` until it has been exercised against the published
starter from a clean checkout.

## Planned Commands And Requests

These commands describe the intended lab contract. Do not treat them as runnable until the starter
and solution references replace `planned` in this lesson's metadata.

```bash
./mvnw clean verify
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

```http
POST /api/demo/reset
POST /api/demo/seed
GET /api/demo/readiness
GET /api/knowledge/search?q=How+can+I+recover+access+to+my+account
POST /api/demo/index
GET /api/knowledge/search?q=How+can+I+recover+access+to+my+account
```

After indexing, your proof must include:

- HTTP 200;
- the expected support article ID;
- a similarity score;
- approved article content;
- category and entity metadata;
- readiness showing five indexed articles.

## Intentional Failure: Search Before Indexing

Seed the application records, but do not index them. Then submit the golden paraphrased query.

The correct result is:

- the API request succeeds;
- no matching vector evidence is returned;
- no support answer is fabricated;
- readiness distinguishes source records from indexed vectors.

Fix the problem by running the explicit indexing operation. Do not add a browser keyword rule, a
canned response, or a prompt that tells an LLM to pretend it retrieved evidence.

This failure teaches the most important lesson in QS-01: configured entities and database rows do not
prove runtime retrieval readiness. The expected evidence ID must return for a known paraphrased query.

## Tests That Must Pass Before Publication

The executable lesson is ready only when its checkpoint proves:

- clean Maven Central dependency resolution;
- application-context discovery of the selected embedding and vector providers;
- no evidence before indexing;
- the expected article ID after indexing;
- preservation of approved metadata;
- no remote embedding call from the local profile;
- successful startup from the packaged application.

## Check Your Understanding

Before moving to the knowledge check, answer these questions in your own words:

1. Why does saving a support article not make it semantic evidence?
2. Which fields should be `@AISearchable`, and which should be `@AIContext`?
3. Why must the document and query embeddings use compatible models and dimensions?
4. What should search return after seed but before index?
5. Why does a generic answer not prove that semantic retrieval worked?

## You Are Done With This Preview When

- you can trace the indexing and search paths without skipping a boundary;
- you can explain what your application owns and what AI Fabric delegates to providers;
- you can distinguish semantic retrieval from RAG;
- you can diagnose the no-evidence state without inventing a fallback;
- you pass the knowledge check.

The lesson becomes executable when immutable starter and solution refs exist, the commands pass from
a clean checkout, the NotebookLM video is reviewed and published, and both coding-assistant prompts
pass validation.

## Reset And Cleanup

The published starter will provide an explicit reset operation that removes only the five course
fixtures and the lesson's local Lucene index. This preview does not provide a reset command because
the standalone application has not been published.

## Troubleshooting Map

| What you observe | What you inspect first |
| --- | --- |
| No evidence after seed | Confirm you ran indexing and readiness reports vectors |
| ONNX startup failure | Confirm the configured model and tokenizer assets exist |
| Lucene dimension error | Confirm the embedding model and index use compatible dimensions |
| Results from the wrong data | Confirm the vector space and Lucene path selected by the active profile |
| Missing metadata | Inspect your application projection and indexing lifecycle |
| Useful answer without evidence | Stop and inspect for a hidden fallback or application-authored response |
| Cloud-key error in the local profile | Confirm ONNX is selected and no live provider is enabled |

## Next Lesson

In CORE-01, you will expand this flow into a complete mental model for AI Fabric modules, ownership,
and provider selection.
