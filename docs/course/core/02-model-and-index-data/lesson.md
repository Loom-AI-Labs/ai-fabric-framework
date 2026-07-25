---
id: core-02
slug: model-and-index-data
title: Model And Index Application Data
track: core
order: 2
durationMinutes: 60
availability: published
courseVersion: 0.4.0-course.4-beta
frameworkVersion: 0.4.0
frameworkTag: ai-fabric-framework-v0.4.0
courseSourceTag: ai-fabric-course-v0.4.0.4
starterRef: course-0.4.0-00-starter
solutionRef: course-0.4.0-01-first-search
requiresOpenAi: false
requiresDocker: false
sourcePaths:
  - docs/course/core/02-model-and-index-data/notebooklm/AI_FABRIC_SEARCHABLE_EVIDENCE_NOTEBOOKLM_SCRIPT.md
  - docs/getting-started/03-first-semantic-search.md
  - docs/getting-started/09-vector-storage-lucene.md
  - ai-infrastructure-module/ai-fabric-core/src/main/java/ai/fabric/annotation/AICapable.java
  - ai-infrastructure-module/ai-fabric-core/src/main/java/ai/fabric/annotation/AISearchable.java
  - ai-infrastructure-module/ai-fabric-core/src/main/java/ai/fabric/annotation/AIContext.java
  - ai-infrastructure-module/ai-fabric-core/src/main/java/ai/fabric/indexing/api/AIEntityIndexingGateway.java
  - ai-infrastructure-module/ai-fabric-core/src/main/java/ai/fabric/indexing/descriptor/AIEntityDescriptorRegistry.java
  - ai-infrastructure-module/ai-fabric-core/src/main/java/ai/fabric/indexing/projection/AIEntityProjectionService.java
  - examples/real-apps/smart-faq-assistant/src/main/resources/ai-entity-config.yml
  - examples/real-apps/smart-faq-assistant/src/main/java/com/ai/fabric/realapps/faq/service/FaqArticleService.java
  - examples/real-apps/smart-faq-assistant/src/test/java/com/ai/fabric/realapps/faq/service/FaqArticleServiceTest.java
theoryVideoIds:
  - searchable-evidence
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

# Model And Index Application Data

## Start Here

Your database records are not automatically AI evidence. A record becomes retrievable only after
your application selects an approved projection, an embedding provider converts that content into a
compatible vector, and the configured vector provider stores the vector with stable identity and
metadata.

In this lesson, you will extend the Support Knowledge Assistant from CORE-01 with a
`KnowledgeArticle` lifecycle. You will define what can be searched, preserve trusted metadata, and
prove create, update, query, and delete behavior. No LLM or cloud key is required.

> **Verified checkpoints:** start from `course-0.4.0-00-starter` and finish at
> `course-0.4.0-01-first-search`. The published solution includes executable HTTP scenarios and
> focused lifecycle tests.

## What You Will Build

The solution checkpoint adds this vertical slice:

```text
KnowledgeArticleController
  -> KnowledgeArticleService -> KnowledgeArticleRepository
                            \
                             -> @AIProcess -> approved projection + durable queue
                                            -> embedding provider -> Lucene

KnowledgeSearchController
  -> AICoreService.performSearch -> embedding provider -> Lucene -> evidence result
```

By the end, you will be able to:

- distinguish domain data, an AI-facing projection, and stored vector evidence;
- choose `@AISearchable` content without exposing the whole entity;
- preserve stable entity, tenant, category, and status metadata;
- explain annotation and YAML precedence;
- prove no-index, create, update, filtered-search, and delete behavior;
- diagnose a successful database write that did not produce current vector evidence.

## Step 1: Define The Evidence Contract

Create `evidence-contract.md` before writing Java. Use this table:

| Field | Domain purpose | AI projection | Reason |
| --- | --- | --- | --- |
| `id` | Stable article identity | Stored entity identity | Connect evidence to the source record |
| `title` | Reader-facing title | Searchable content | Carries the article's primary meaning |
| `body` | Approved support guidance | Searchable content | Contains the answer users need to retrieve |
| `category` | Support taxonomy | Context metadata | Supports filtering and evidence display |
| `tenantId` | Ownership boundary | Context metadata | Supports later trusted tenant filtering |
| `status` | Publication state | Context metadata | Lets the app exclude non-published evidence |
| `internalNotes` | Staff-only workflow | Excluded | Must not enter embeddings, metadata, or responses |

Your projection is a data policy. `@AICapable` and field configuration describe that policy; they do
not transfer ownership of the record to AI Fabric.

## Step 2: Model Approved Fields

The migrated 0.4 lesson uses the current annotation contract:

```java
@Entity
@AICapable(entityType = "knowledge-article")
public class KnowledgeArticle {
    @Id
    @AIIdentity
    @AIContext(
        key = "entityId",
        dataType = AIContextDataType.ID,
        destinations = {
            AIContextDestination.VECTOR_METADATA,
            AIContextDestination.API_RESPONSE
        },
        priority = 100,
        required = true
    )
    private String id;

    @AISearchable(priority = 100, required = true)
    private String title;

    @AISearchable(maxLength = 8_000, priority = 80, required = true)
    private String body;

    @AIContext(
        dataType = AIContextDataType.STRING,
        destinations = {
            AIContextDestination.VECTOR_METADATA,
            AIContextDestination.LLM_CONTEXT,
            AIContextDestination.API_RESPONSE
        },
        description = "Support taxonomy used for filtering",
        priority = 70
    )
    private String category;

    @AIContext(
        dataType = AIContextDataType.ID,
        destinations = {AIContextDestination.VECTOR_METADATA},
        description = "Application-owned tenant identifier",
        priority = 100,
        required = true
    )
    private String tenantId;

    @AIContext(description = "Publication state")
    private String status;

    private String internalNotes;
}
```

Read the annotations literally:

- `@AICapable` resolves the entity type and capability configuration.
- `@AISearchable` marks approved prose extracted for embedding and search.
- `@AIContext` contributes approved structured metadata; it does not make a field searchable prose.
- An ordinary field such as `internalNotes` remains outside the AI-facing projection.

Do not accept a design that serializes the complete entity and removes sensitive fields afterward.
Select approved fields first.

## Step 3: Resolve Annotation And YAML Configuration

Annotation-backed entities need no YAML entry. Add one only when deployment policy needs an explicit
operational override:

```yaml
ai-entities:
  knowledge-article:
    indexing:
      enabled: true
      max-characters: 8000
    analysis:
      enabled: false
```

`@AICapable.entityType` is canonical for typed entities. Field annotations define the approved
projection. YAML may apply supported operational and field overrides, but it cannot change typed
identity or widen a field's security destinations. YAML-only push entities must explicitly enable
indexing and declare their fields.

Add a focused configuration test with these assertions:

```text
resolved entity type = knowledge-article
indexing = enabled
searchable fields include title and body
context fields include entityId, category, tenantId, status
internalNotes is absent from searchable and context projections
projection hash is stable
```

The test protects intent. It still does not prove that a vector exists.

## Step 4: Make The Lifecycle Explicit

Declare lifecycle intent on public Spring service methods:

```java
@Transactional
@AIProcess(operation = AIProcessOperation.CREATE)
public KnowledgeArticle create(KnowledgeArticle article) {
    return repository.saveAndFlush(article);
}

@Transactional
@AIProcess(operation = AIProcessOperation.UPDATE)
public KnowledgeArticle update(String id, UpdateArticle request) {
    KnowledgeArticle article = requireAuthorizedArticle(id);
    article.apply(request);
    return repository.saveAndFlush(article);
}

@Transactional
@AIProcess(operation = AIProcessOperation.DELETE)
public KnowledgeArticle delete(String id) {
    KnowledgeArticle article = requireAuthorizedArticle(id);
    repository.delete(article);
    repository.flush();
    return article;
}
```

The aspect resolves the returned entity, builds an allowlisted `AIIndexDocument`, and inserts durable
work in the source transaction. Provider work starts only after commit. A rollback leaves no committed
queue row and causes no vector mutation.

For the learning checkpoint, configure `IndexingStrategy.SYNC`. It attempts provider work after commit
so the test can observe final vector state immediately. If the provider fails, work remains retryable
instead of making the committed source record disappear. `ASYNC` and `BATCH` require proof of queue
acceptance, worker completion, retry/dead-letter state, and final vector readiness.

For a boundary that cannot use Spring AOP, call `AIEntityIndexingGateway.upsert(...)` or
`delete(...)`. Do not pair raw embedding and vector calls to emulate an entity lifecycle.

## Step 5: Prove Search Before And After Indexing

Seed this article without invoking the indexing path:

```json
{
  "id": "article-account-lockout",
  "title": "Recover a locked account",
  "body": "Wait fifteen minutes, then use account recovery to verify your email and reset access.",
  "category": "authentication",
  "tenantId": "tenant-blue",
  "status": "PUBLISHED"
}
```

Search with different wording:

```text
How can I sign in after too many failed attempts?
```

Before indexing, the expected response is an empty evidence list. After indexing, the expected
evidence contains:

```json
{
  "entityType": "knowledge-article",
  "entityId": "article-account-lockout",
  "content": "Recover a locked account ...",
  "score": "a numeric similarity score",
  "metadata": {
    "category": "authentication",
    "tenantId": "tenant-blue",
    "status": "PUBLISHED"
  }
}
```

The exact score is provider- and corpus-dependent. Assert identity, content, required metadata, and
relative retrieval behavior instead of inventing a universal percentage.

## Step 6: Run The Complete Lifecycle Test

Add one integration test that performs all of these operations in order:

1. save an article through an unannotated fixture path and prove the query returns no matching evidence;
2. submit it through `AIEntityIndexingGateway` and prove a paraphrased query returns the stable entity ID;
3. prove `category`, `tenantId`, and `status` are present in evidence metadata;
4. update the title and body, reindex the same ID, and prove stale text is no longer returned;
5. delete the article and remove its vector;
6. prove the entity cannot be retrieved and no stale vector remains.

Also keep focused unit tests for request construction and malformed result rows. The full lifecycle
test protects provider integration; the focused tests explain failures quickly.

### Expected Test Report

```text
source records before seed: 0
source records after seed: 1
matching vectors before index: 0
matching vectors after index: 1
stable entity id preserved: yes
required metadata present: yes
stale content after update: 0
matching vectors after delete: 0
```

## Step 7: Trigger And Correct The Metadata Failure

Remove `tenantId` from the AI-facing metadata configuration, reindex the article, and run the
lifecycle test.

The test must fail even if semantic retrieval still finds the article. The evidence is incomplete
for later tenant enforcement.

Correct it by restoring `tenantId`, rebuilding the affected vector, and rerunning the same test.

### Why This Failure Matters

Metadata added only at query time cannot repair vectors that were indexed without the trusted value.
Later lessons can enforce tenant policy only when identity comes from trusted application context
and indexed evidence carries the required scope.

## Commands And Requests

```bash
./mvnw clean verify
./scripts/download-onnx-model.sh
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

```http
POST /api/demo/reset
POST /api/demo/seed
GET /api/knowledge/search?q=How+can+I+sign+in+after+too+many+failed+attempts
POST /api/demo/index
GET /api/knowledge/search?q=How+can+I+sign+in+after+too+many+failed+attempts
PUT /api/knowledge/articles/article-account-lockout
DELETE /api/knowledge/articles/article-account-lockout
GET /api/demo/readiness
```

Open `requests/01-semantic-search.http` in the solution checkpoint for complete copyable requests.

## Common Mistakes

| Mistake | Observable risk | Correct approach |
| --- | --- | --- |
| Treating a database row as indexed evidence | Search is empty while the UI claims data is ready | Measure source and vector readiness separately |
| Marking every field searchable | Sensitive or irrelevant data enters embeddings | Define an allowlisted projection |
| Changing the embedding model without rebuilding | Query and stored vectors become incompatible | Treat model, dimensions, and index path as schema |
| Generating a new entity ID on update | Old vectors survive beside the new record | Reindex the same stable identity |
| Deleting only the database row | Stale evidence remains retrievable | Remove the vector and prove absence |
| Testing only the indexing method call | Logged/skipped provider work can look successful | Assert stored and retrieved evidence |
| Using a fixed score assertion | A legitimate provider or corpus change breaks the test | Assert expected identity and bounded ranking behavior |

## Troubleshooting

| Symptom | Inspect |
| --- | --- |
| No evidence after indexing | Resolved entity config, stable `id`, approved content, provider availability, and vector path |
| Search returns the wrong entity type | `entityType` on stored vectors and `AISearchRequest` |
| Metadata is missing | `@AIContext`, YAML metadata fields, and the stored vector record |
| Updates return old wording | Stable identity and the update/reindex path |
| Deleted records still appear | Application delete event and `AIEntityIndexingGateway.delete(...)` proof |
| Source committed but vector is delayed | Queue status, worker enablement, retry count, and dead-letter state |
| Source rolled back but queue exists | Source and indexing repositories are not sharing the expected transaction manager |
| Search fails after a model change | Embedding dimensions and index compatibility |

## Done When

You are done with this lesson when:

- your evidence contract excludes unapproved domain fields;
- annotation/YAML resolution is covered by a focused test;
- the no-index state returns no evidence rather than a canned answer;
- create, update, search, and delete are proven against vector state;
- required metadata survives indexing and retrieval;
- the intentional metadata failure is observable and corrected;
- you score at least 80 percent on the knowledge check.

## Next Lesson

CORE-03 uses this searchable evidence to build a RAG response that cites source IDs, refuses to
invent support guidance when evidence is absent, and keeps retrieval distinct from generation.
