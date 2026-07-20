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
  targetDurationMinutes: 4
  title: From Application Record To Semantic Evidence
  publicUrl: null
  transcript: notebooklm/00-lesson-brief.md
  sourceManifest: notebooklm/source-manifest.yml
---

# First Useful Result

## Outcome

Understand and exercise the smallest useful AI Fabric workflow: application-owned support knowledge
becomes vector evidence and a differently worded query retrieves the expected article.

This lesson is currently a course UI preview. Its conceptual material and knowledge check are ready.
The standalone learner repository, immutable starter/solution checkpoints, and reviewed NotebookLM
recording must be published before this lesson becomes an executable public lab.

## Why This Matters

Semantic retrieval is the foundation beneath later RAG, recommendations, and policy-aware workflows.
It is also where a common misunderstanding begins: records in an application database are not
automatically available to AI orchestration. The application must project approved content, generate
an embedding, store a vector with stable metadata, and query the same compatible vector space.

## NotebookLM Pre-Lesson Theory

- Why semantic similarity differs from keyword search and from asking a language model to answer
  from general knowledge.
- The request/data flow from an application record to projected content, ONNX embedding, Lucene
  vector, query embedding, similarity search, and returned evidence.
- Ownership boundaries between the application, AI Fabric, the embedding provider, and vector
  storage.
- Why database rows do not become retrievable evidence until indexing succeeds and why no evidence
  is safer than an invented answer.

## Architecture And Request Flow

```text
KnowledgeArticle record
  -> application chooses approved fields and metadata
  -> AI Fabric requests an embedding
  -> local ONNX provider returns a numeric vector
  -> Lucene stores vector + entity ID + content + metadata

Learner query
  -> AI Fabric requests a query embedding
  -> Lucene similarity search
  -> AI Fabric search response
  -> application returns evidence ID, score, content, and metadata
```

Ownership map:

| Boundary | Owns |
| --- | --- |
| Application | Support article lifecycle, approved content, stable IDs, categories, endpoint DTO |
| AI Fabric | Embedding and vector-service contracts, entity configuration, search request/response |
| ONNX provider | Local text-to-vector inference |
| Lucene provider | Vector persistence and similarity lookup |
| Browser | Displays the API result; it does not manufacture retrieval intelligence |

## Starting State

The final learner repository will provide a Java 21 and Spring Boot 4.1.x starter application with:

- Maven wrapper;
- AI Fabric `0.3.3` BOM import;
- no cloud credentials;
- a local profile;
- empty support-knowledge fixtures;
- reset, seed, index, readiness, and search endpoints.

The starter and solution refs remain deliberately `planned` during this UI preview. Do not present
the commands below as a validated clean-checkout lab until those refs exist.

## Files The Executable Lab Will Use

```text
pom.xml
src/main/java/.../SupportAssistantApplication.java
src/main/java/.../knowledge/KnowledgeArticle.java
src/main/java/.../knowledge/KnowledgeArticleService.java
src/main/java/.../web/KnowledgeSearchController.java
src/main/resources/application-local.yml
src/main/resources/ai-entity-config.yml
```

## Manual Build Path

The reviewed executable version will guide the learner through:

1. Resolve AI Fabric from Maven Central using the published BOM.
2. Add the starter, Spring AI provider bridge, ONNX starter, and Lucene vector module.
3. Configure local ONNX model/tokenizer paths and a dimension-compatible Lucene index path.
4. Model `KnowledgeArticle` with approved searchable content and category metadata.
5. Seed five application records without indexing them.
6. Search before indexing and observe the explicit no-evidence state.
7. Index the records.
8. Repeat the paraphrased query and inspect entity ID, score, content, and metadata.

## Coding-Assistant Path

The coding-assistant path uses the same starter and behavior contract. It does not copy the solution
checkpoint. The assistant must inspect current AI Fabric APIs, keep domain state in the application,
reproduce the no-index state, and run the same tests as the manual path.

The implementation prompt is marked `planned` until the standalone starter is available and the
prompt has been exercised from a clean checkout.

## Planned Commands And Requests

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

Expected evidence after the published lab is indexed:

- HTTP 200;
- one known support article ID in the result set;
- a similarity score;
- approved article content;
- category and entity metadata;
- readiness showing five indexed articles.

## Intentional Failure

Search after seed but before index.

Expected result:

- the API request succeeds;
- the result contains no matching evidence;
- the application does not fabricate a support answer;
- readiness distinguishes source records from indexed vectors.

The correction is an explicit indexing operation, not a UI fallback or a prompt that tells the model
to pretend records were retrieved.

## Field Lesson

Configured entities and database rows do not prove runtime retrieval readiness. Indexing is a data
lifecycle operation. Readiness must be demonstrated by the expected evidence ID returning for a
golden paraphrased query.

## Tests Required Before Publication

- Clean Maven Central dependency resolution.
- Application context discovers the required embedding and vector providers.
- Search before indexing returns no evidence.
- Search after indexing returns the expected article ID.
- Response preserves approved metadata.
- Local profile performs no remote embedding call.
- Packaged application starts with the same provider posture.

## Done When

For this preview:

- the learner can trace the complete request/data path;
- the learner can identify each ownership boundary;
- the knowledge check is passed.

For public executable status:

- immutable starter and solution refs exist;
- commands pass from a clean checkout;
- the reviewed NotebookLM video is published;
- the intentional failure and retrieval proof are recorded;
- the implementation and review prompts pass validation.

## Reset And Cleanup

The published learner app will expose an explicit reset operation that removes course fixture data
and the local lesson index without touching unrelated paths. Until the app exists, there is no reset
command to run from this preview.

## Troubleshooting

| Symptom | Inspect first |
| --- | --- |
| No evidence after seed | Confirm the index operation ran and readiness reports vectors |
| ONNX startup failure | Confirm model and tokenizer assets exist at configured paths |
| Lucene dimension error | Confirm embedding dimensions and index path describe the same model |
| Cloud-key error in local profile | Confirm ONNX is selected and no live provider is enabled |
| Useful answer without evidence | Stop: inspect for a hidden fallback or application-authored response |

## Next Lesson

CORE-01 will turn this first flow into a full ownership and module-selection mental model.

