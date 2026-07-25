# NotebookLM Single-Source Production Script: From Application Data To Searchable Evidence

## Generator Instructions - Do Not Narrate

Use this file as the only source for the video. Do not supplement it with general RAG knowledge,
external documentation, or assumptions about AI Fabric. Do not require another source file.

Create a structured technical explainer titled **From Application Data To Searchable Evidence With
AI Fabric**. Follow the twelve scenes in order. Use each **Visual** block as production direction and
each **Narration** block as the spoken message. Natural transitions are allowed, but do not omit,
replace, or contradict the technical claims in the narration.

This is the theoretical introduction to CORE-02, not a code-along. Keep AI Fabric's current indexing
and semantic-search architecture as the subject. Do not invent APIs, endpoints, properties, vector
provider behavior, test output, benchmarks, or completion claims. Apply the final accuracy
guardrails to the complete output.

## Production Direction

- Title: **From Application Data To Searchable Evidence With AI Fabric**
- Target duration: 9-12 minutes.
- Audience: Java and Spring Boot developers who understand the AI Fabric architecture and are ready
  to model and prove the first indexing lifecycle.
- Voice: direct, practical, calm, and technically precise. Address the developer as **you**.
- Primary objective: make the data projection, indexing lifecycle, provider boundaries, and search
  proof predictable before the developer edits the course application.
- Example domain: a Spring Boot Support Knowledge Assistant with application-owned
  `KnowledgeArticle` records.
- Example local providers: AI Fabric ONNX embeddings and AI Fabric Lucene vector storage. No LLM or
  API key is required for the explained retrieval path.
- Visual style: use a domain record, field-projection table, indexing and query flows, lifecycle
  diagrams, and one concise Java entity. Avoid generic AI imagery and invented UI results.

## Scene 1: Saving A Record Is Not Indexing Evidence

**Visual:** Show five `KnowledgeArticle` rows in an application database. Beside them, show an empty
Lucene vector index. A user asks, "How can I recover access to my account?"

**Narration:**

Your application can contain five valid support articles and still return no semantic-search
evidence.

The database proves that the domain records exist. It does not prove that approved text was
projected, embedded, and stored in the configured vector index. Those are separate lifecycle facts.

This distinction is the foundation of CORE-02. You will model an application entity for AI Fabric,
make the index lifecycle explicit, and prove create, update, search, and delete behavior.

The local course path uses ONNX to create embeddings and Lucene to store and search vectors. It does
not need an LLM. Semantic retrieval should work and fail honestly before generated answers are
introduced.

## Scene 2: Separate Domain Data, Projection, And Evidence

**Visual:** Build three connected boxes.

```text
Domain record
  -> approved AI-facing projection
  -> stored vector evidence
```

Label their contents:

```text
Domain record: all application fields and business lifecycle
Projection: searchable text plus approved structured metadata
Evidence: stable identity, content, vector, metadata, and retrieval score
```

**Narration:**

Keep three representations separate.

The domain record is the source of truth owned by your application. It may contain fields that AI
Fabric must never see.

The AI-facing projection is the approved subset. Searchable prose contributes semantic meaning.
Structured context, such as an ID, tenant, category, or status, travels as metadata when approved.

Stored vector evidence is the provider representation created after indexing. It combines identity,
approved content, a numeric vector, and metadata. A later search result adds a ranking score.

The projection is an application policy decision. AI Fabric coordinates its processing. The
embedding provider creates the vector. The vector provider persists and retrieves it.

## Scene 3: Describe The Projection With Current AI Fabric Contracts

**Visual:** Show this reviewed conceptual entity and highlight one field at a time.

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

    @AISearchable(priority = 100, required = true)
    private String title;

    @AISearchable(priority = 80, required = true)
    private String body;

    @AIContext
    private String category;

    @AIContext
    private String tenantId;
}
```

**Narration:**

`AICapable` identifies the stable entity type and its default lifecycle strategy.

`AIIdentity` explicitly marks the stable source identity. JPA identity can also resolve the same
contract, but an explicit marker makes the projection easy to review.

`AISearchable` marks approved text that is extracted and embedded for semantic search. The title and
body describe what the article means, so they belong in the searchable projection.

`AIContext` marks structured metadata that is not embedded as searchable prose. The stable ID links
evidence back to the source record. Category can support filtering or response context. Tenant ID can
support a later access boundary, but only when trusted application context and provider filtering
also enforce that boundary.

Do not mark every domain field. Projection is deliberate data minimization, not serialization of the
whole entity.

## Scene 4: Understand Scoped Annotation And YAML Ownership

**Visual:** Show two configuration inputs flowing into one resolved `AIEntityConfig`.

```text
Java annotations ----\
                      -> immutable resolved entity descriptor
typed YAML policy ----/

No global YAML-versus-annotation winner
```

**Narration:**

AI Fabric compiles one validated entity descriptor from typed declarations.

For a Java entity, `AICapable.entityType` is canonical. `AISearchable` and `AIContext` define the
approved projection and destinations. Typed YAML can disable indexing, set a projection character
budget, enable optional analysis, or apply supported field overrides. It cannot replace typed
identity or widen security destinations.

A YAML-only push entity has no Java contract, so it must explicitly enable indexing and declare its
searchable and metadata fields.

Priority controls projection order and which fields survive a bounded character budget. It does not
change provider similarity scoring. True weighted retrieval is a separate capability, not an
annotation side effect.

## Scene 5: Follow The Transaction-Aware Indexing Gateway

**Visual:** Animate this flow and split it at the indexing strategy.

```text
application lifecycle event
  -> @AIProcess with explicit CREATE, UPDATE, or DELETE
  -> resolve entity descriptor and approved AIIndexDocument
  -> persist projected queue work with the source transaction
  -> source rollback: no committed work and no provider mutation
  -> source commit:
       SYNC: attempt provider work after commit
       ASYNC/BATCH: worker leases durable work
```

**Narration:**

The indexing module turns explicit lifecycle intent into durable, approved work.

`AIProcess.operation` is the only operation source; Java method names have no lifecycle meaning. The
default target resolver handles direct entities, optionals, collections, arrays, and Hibernate
proxies. Application result wrappers and void deletes use a declared target resolver. A path that
cannot use Spring AOP calls `AIEntityIndexingGateway` directly.

The queue stores a versioned `AIIndexDocument`, not a serialized Java entity or class name. The
document contains only approved search text, RAG text, destination-specific metadata, source version,
descriptor hash, and correlation evidence.

Both synchronous and asynchronous strategies are durable. `SYNC` means attempt provider work after
source commit, not inside the source transaction. Failure remains visible and retryable. `ASYNC` and
`BATCH` defer processing to workers. Ordering state prevents stale work from overwriting a newer
update or recreating a deleted vector.

## Scene 6: Follow Projection, Embedding, And Storage

**Visual:** Show the internal capability path.

```text
KnowledgeArticle
  -> AIEntityDescriptorRegistry
  -> AIEntityProjectionService
  -> durable AIIndexDocument
  -> IndexingOperationExecutor
  -> EmbeddingProvider
  -> numeric vector
  -> VectorManagementService
  -> VectorDatabaseService
  -> Lucene
```

**Narration:**

During indexing, the descriptor registry validates identity, searchable fields, context destinations,
required values, and operational policy. The projection service builds destination-specific,
bounded content before it reaches durable storage. Requested PII sanitization fails closed.

The operation executor embeds semantic-search text through `AIEmbeddingService` and the configured
`EmbeddingProvider`. It performs exactly one vector upsert for a create or update and one idempotent
delete for a delete. Optional analysis is separate dependent work rather than a hidden second
embedding path.

For this lesson, the ONNX provider performs local text-to-vector inference. AI Fabric then asks
`VectorManagementService` to store the entity type, stable entity ID, approved content, vector, and
metadata through the configured `VectorDatabaseService`. Lucene owns the local vector persistence
and similarity operations.

Each stored part has a role. The vector represents semantic meaning. The stable ID preserves
identity. Content makes the returned evidence inspectable. Metadata supports filtering, context, and
later policy checks.

If the entity has no stable ID, no approved searchable text, no matching configuration, or no
configured provider, the lifecycle cannot be considered ready merely because the domain record was
saved.

## Scene 7: Treat Embedding Compatibility As A Contract

**Visual:** Show indexed content and a query entering the same embedding space. Then show an
incompatible vector dimension being rejected from that space.

**Narration:**

Indexed content and future queries must use compatible embeddings.

An embedding model determines how text becomes a vector and how many dimensions that vector has.
Similarity is meaningful only when content and query vectors belong to the same compatible space.

Changing the model, dimensions, preprocessing, vector location, or entity-space convention can
invalidate existing evidence. Treat those choices as index schema. A provider change may require a
controlled rebuild rather than a configuration flip over old vectors.

The application should expose enough readiness information to distinguish source-record count,
indexed-vector count, provider configuration, and build identity. That evidence makes stale or mixed
indexes diagnosable.

## Scene 8: Follow A Semantic Search Request

**Visual:** Animate the query path into evidence results.

```text
"How can I recover access to my account?"
  -> AISearchRequest
  -> query embedding through the compatible EmbeddingProvider
  -> vector similarity search
  -> result limit, threshold, entity type, and approved filters
  -> evidence identity + content + metadata + ranking score
```

**Narration:**

At query time, the application builds an `AISearchRequest` with a natural-language query and the
required entity type, result limit, threshold, filters, or request metadata.

`AICoreService` creates the query embedding and delegates to AI Fabric search services. The vector
provider compares that query vector with indexed vectors and returns nearby evidence.

The score ranks similarity within this retrieval operation. It is not a probability that an answer
is correct and it is not a universal confidence percentage. Threshold and limit decide which stored
neighbors may be returned. They cannot create evidence that was never indexed.

The application then projects the approved evidence into its REST response or supplies it to a later
RAG step.

## Scene 9: Preserve Metadata Without Treating It As Decoration

**Visual:** Show the same semantic query with three metadata scopes.

```text
entityType = knowledge-article
category = account-access
tenantId = trusted current tenant
```

**Narration:**

Metadata is part of the retrieval contract, not an optional label added for display.

Entity type keeps unrelated classes out of one search. Category can narrow a support domain. Tenant
metadata can participate in isolation when it comes from trusted application identity and the chosen
vector provider supports the required filter.

The metadata must be present when the vector is written, retained when it is updated, and applied by
the backend when it is searched. Adding a tenant ID to a client request is not tenant security. A
missing provider capability must fail closed rather than silently widening retrieval.

CORE-02 introduces stable metadata because later RAG and security lessons depend on it. The lab's
intentional failure removes expected metadata so a test can prove that projection and reindexing are
real lifecycle responsibilities.

## Scene 10: Prove Create, Update, Delete, And Backfill

**Visual:** Show one article moving through four states while a golden query runs after each relevant
operation.

```text
CREATE -> vector appears
UPDATE -> searchable content and metadata change
DELETE -> vector disappears
BACKFILL -> pre-existing records gain current vectors
```

**Narration:**

An index is a derived projection, so it must follow the domain lifecycle.

Create proof shows that a new source record becomes retrievable. Update proof changes meaningful
content or metadata, reindexes the same stable identity, and shows that search reflects the new
projection rather than a duplicate or stale vector. Delete proof removes the vector and confirms the
record is no longer retrievable. Backfill proof processes records that existed before indexing was
enabled or after the index schema changed.

AI Fabric supplies synchronous, asynchronous, batch, queue, migration, and vector lifecycle pieces.
Your application chooses where its domain events invoke them and how operations are observed.

One successful search after an initial seed is not complete lifecycle coverage.

## Scene 11: Diagnose The No-Evidence State Honestly

**Visual:** Show a diagnostic tree beginning with "Search returned zero evidence."

```text
Are source records present?
Were indexing operations requested?
Did sync work finish, or did the queue worker complete?
Was approved content non-empty?
Were stable ID and required metadata stored?
Do content and query use compatible embeddings?
Is the application searching the expected entity space and index location?
```

**Narration:**

When semantic search returns no evidence, keep the failure visible.

First distinguish source readiness from vector readiness. Then inspect the indexing trigger and
strategy. For queued work, inspect processing and retry state. Verify the projected content, stable
ID, metadata, model compatibility, vector provider, and index location. Finally inspect entity type,
threshold, and filters on the query.

Do not add browser keyword matching, a canned response, or generic LLM knowledge to make the screen
look successful. Those fallbacks hide the broken boundary.

The correct CORE-02 before-index result is no evidence. After explicit indexing, the same
paraphrased query should return the expected article identity, approved content, metadata, and a
ranking score. That comparison is the proof.

## Scene 12: Hand Off From Retrieval To RAG

**Visual:** Keep retrieval highlighted and add RAG as a later stage.

```text
CORE-02 semantic retrieval:
question -> query vector -> stored evidence

Later evidence-grounded RAG:
question -> stored evidence -> approved context -> LLM -> grounded answer
```

**Narration:**

CORE-02 stops at searchable evidence.

You now know which application fields are approved, how configuration resolves, how lifecycle work is
coordinated, where embeddings are created, what the vector provider stores, and how a semantic query
returns inspectable evidence.

The next RAG lesson will add generation after this evidence boundary. Keeping the stages separate
lets you answer two independent questions: did retrieval find the correct source, and did the model
use that source correctly?

Before starting the practical lesson, predict the intentional first result: five support articles
exist in the application database, but no indexing operation has completed. What should semantic
search return, and which readiness values would prove why?

## Final Request And Ownership Reference - Do Not Narrate As A List

Use this table to verify all generated diagrams and labels.

| Stage | Input | Responsible owner | Output to prove |
| --- | --- | --- | --- |
| Domain persistence | Application command | Spring Boot application | Source record and stable ID |
| Projection | Entity plus annotations/YAML | Application policy expressed through AI Fabric configuration | Approved searchable text and metadata |
| Coordination | Lifecycle event plus action plan | `ai-fabric-indexing` | Immediate execution or observable queue request |
| Embedding | Approved text | Configured `EmbeddingProvider` through AI Fabric | Compatible numeric vector |
| Vector persistence | Entity type, ID, content, vector, metadata | Configured `VectorDatabaseService` | Stored vector evidence |
| Query embedding | `AISearchRequest.query` | Compatible embedding provider through AI Fabric | Query vector |
| Similarity search | Query vector plus scope | Configured vector provider | Ranked evidence results |
| API response | Approved evidence | Spring Boot application | Stable, non-fabricated response contract |

## Accuracy Guardrails For NotebookLM

- Keep AI Fabric's indexing and semantic-search path as the subject.
- Do not describe a database row as vector evidence before indexing completes.
- Do not imply that annotations or YAML execute indexing by themselves.
- State that YAML remains authoritative when generated annotation metadata conflicts with it.
- Do not invent a separate `AIEmbeddable` annotation. Explain that embeddable fields exist in entity
  configuration and that annotation-driven searchable content is used as embeddable content.
- Do not imply that asynchronous queue acceptance proves vector readiness.
- Do not imply that an LLM or API key is required for the ONNX and Lucene retrieval path.
- Do not call a similarity score a probability, calibrated confidence, or correctness guarantee.
- Do not treat client-supplied tenant metadata as authorization.
- Do not claim that every vector provider supports every filter; required capability must be verified.
- Do not invent methods, endpoints, properties, index counts, record IDs, exact scores, terminal
  output, or checkpoint results.
- Do not describe fallback text, browser keyword matching, or generic LLM knowledge as retrieval.
- Do not claim the future learner checkpoint, lab, or generated video has already passed review.
- Do not add performance, accuracy, compliance, or production-readiness claims.
