---
id: core-02
slug: model-and-index-data
title: Model And Index Application Data
track: core
order: 2
durationMinutes: 60
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
  - docs/course/core/02-model-and-index-data/notebooklm/AI_FABRIC_SEARCHABLE_EVIDENCE_NOTEBOOKLM_SCRIPT.md
  - docs/getting-started/03-first-semantic-search.md
  - docs/getting-started/09-vector-storage-lucene.md
  - ai-infrastructure-module/ai-fabric-core/src/main/java/ai/fabric/annotation/AICapable.java
  - ai-infrastructure-module/ai-fabric-core/src/main/java/ai/fabric/annotation/AISearchable.java
  - ai-infrastructure-module/ai-fabric-core/src/main/java/ai/fabric/annotation/AIContext.java
  - ai-infrastructure-module/ai-fabric-core/src/main/java/ai/fabric/service/AICapabilityService.java
  - examples/real-apps/smart-faq-assistant/src/main/resources/ai-entity-config.yml
  - examples/real-apps/smart-faq-assistant/src/main/java/com/ai/fabric/realapps/faq/service/FaqArticleService.java
  - examples/real-apps/smart-faq-assistant/src/test/java/com/ai/fabric/realapps/faq/service/FaqArticleServiceTest.java
theoryVideoIds:
  - searchable-evidence
assistant:
  mode: implement
  implementationPrompt: assistant-prompt.md
  reviewPrompt: assistant-review-prompt.md
  validationStatus: planned
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

> **Current lesson status: Preview.** The lesson, searchable-evidence theory video, assistant
> prompts, and knowledge check are ready. The standalone starter and immutable checkpoint refs are
> still `planned`, so the commands and endpoint names below define the reviewed lab contract rather
> than a clean-checkout completion claim.

## What You Will Build

The executable checkpoint will add this vertical slice:

```text
KnowledgeArticleController
  -> KnowledgeArticleService -> KnowledgeArticleRepository
                            \
                             -> AICapabilityService -> embedding provider -> Lucene

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

The planned starter will use the current annotation contract:

```java
@Entity
@AICapable(entityType = "knowledge-article")
public class KnowledgeArticle {
    @Id
    private String id;

    @AISearchable(weight = 2.0, required = true)
    private String title;

    @AISearchable(maxLength = 8_000, required = true)
    private String body;

    @AIContext(description = "Support taxonomy used for filtering")
    private String category;

    @AIContext(description = "Application-owned tenant identifier")
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

Add an explicit entity entry so the enabled capabilities are reviewable:

```yaml
ai-entities:
  knowledge-article:
    entity-type: knowledge-article
    features: [embedding, search]
    auto-embedding: true
    indexable: true
    enable-search: true
    metadata-fields:
      - name: category
        type: string
      - name: tenantId
        type: string
      - name: status
        type: string
```

AI Fabric can discover searchable and context fields from annotations. YAML can explicitly configure
entity behavior and fields, and explicit YAML wins when it conflicts with annotation defaults.

Add a focused configuration test with these assertions:

```text
resolved entity type = knowledge-article
features include embedding and search
indexable = true
metadata fields include category, tenantId, status
internalNotes is absent from searchable, embeddable, and metadata fields
```

The test protects intent. It still does not prove that a vector exists.

## Step 4: Make The Lifecycle Explicit

Keep repository persistence and vector lifecycle visible in the application service:

```text
create:
  save domain record -> process saved record for AI -> prove retrievable evidence

update:
  load and authorize record -> save changed record -> reindex same entity identity

delete:
  load and authorize record -> delete source record -> remove vector by entity type and ID

backfill:
  page existing records -> index each approved record -> record success/failure totals
```

For the learning checkpoint, use synchronous verification around local providers so each test can
observe the final vector state. A production application may enqueue asynchronous work, but then its
proof must include queue acceptance, worker completion, retry/dead-letter state, and final vector
readiness.

One important implementation detail is visible in the current capability service: processing and
storage paths can log and return after missing content, missing identity, or provider failure. A
successful domain save is therefore not enough. Assert the resulting evidence.

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

1. save an article without indexing and prove the query returns no matching evidence;
2. index it and prove a paraphrased query returns the stable entity ID;
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

## Planned Commands And Requests

These become executable when the starter and solution refs are published:

```bash
./mvnw clean verify
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

```http
POST /api/demo/reset
POST /api/knowledge/seed-without-index
GET /api/knowledge/search?q=How+can+I+sign+in+after+too+many+failed+attempts
POST /api/knowledge/index
GET /api/knowledge/search?q=How+can+I+sign+in+after+too+many+failed+attempts
PUT /api/knowledge/article-account-lockout
DELETE /api/knowledge/article-account-lockout
GET /api/demo/vector-readiness
```

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
| Deleted records still appear | Application delete event and `removeVector(entityType, entityId)` proof |
| Search fails after a model change | Embedding dimensions and index compatibility |

## Done When

You are done with this preview lesson when:

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
