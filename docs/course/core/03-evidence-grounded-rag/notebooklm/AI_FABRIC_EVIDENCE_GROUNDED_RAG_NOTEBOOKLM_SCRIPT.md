# NotebookLM Single-Source Production Script: Evidence-Grounded RAG

## Generator Instructions - Do Not Narrate

Use this file as the only source for the video. Do not supplement it with general RAG knowledge,
external documentation, or assumptions about AI Fabric. Do not ask for or rely on another source.

Create a structured technical explainer titled **Evidence-Grounded RAG With AI Fabric: From
Retrieval To Verifiable Answer**. Follow the fourteen scenes in order. Use every **Visual** block as
production direction and every **Narration** block as the spoken message. Natural transitions are
allowed, but do not omit, replace, or contradict the technical content.

This is the theoretical introduction to CORE-03, not a code-along. Keep AI Fabric's current basic
RAG and orchestration contracts as the subject. Do not invent classes, methods, properties,
endpoints, provider behavior, scores, indexed records, test output, benchmarks, or completion
claims. Apply the final accuracy guardrails to the complete output.

## Production Direction

- Title: **Evidence-Grounded RAG With AI Fabric: From Retrieval To Verifiable Answer**
- Target duration: 12-15 minutes.
- Audience: Java and Spring Boot developers who understand AI Fabric's architecture and have already
  proved that application data can be indexed and retrieved semantically.
- Voice: direct, practical, calm, and technically precise. Address the developer as **you**.
- Primary objective: make retrieval eligibility, evidence selection, context construction,
  generation, evidence projection, and failure states independently understandable and testable.
- Example application: the continuing Spring Boot Support Knowledge Assistant with indexed
  `KnowledgeArticle` evidence.
- Example question: **How can I recover access to my account?**
- Visual style: use one persistent horizontal RAG flow, an ownership map, evidence cards, bounded
  context, and a failure matrix. Avoid generic AI imagery and decorative chatbot scenes.

## Scene 1: Add Generation Only After Retrieval Works

**Visual:** Begin with the completed CORE-02 retrieval flow, then add one new stage.

```text
CORE-02
question -> query embedding -> vector search -> retrieved evidence

CORE-03
question -> retrieved evidence -> approved context -> generation -> answer + evidence
```

Show three distinct output cards: **retrieved documents**, **generated answer**, and **diagnostics**.

**Narration:**

Evidence-grounded RAG begins after searchable evidence already works.

In CORE-02, you proved that approved application content could become vectors and return as ranked
documents. CORE-03 adds generation after that boundary. The model receives a bounded context built
from retrieved evidence and writes a user-facing answer. The application returns the answer together
with the evidence and diagnostics needed to inspect what happened.

Keep those artifacts separate. A retrieved document came from an indexed source. Context is a
selected representation prepared for the model. The answer is newly generated wording. Diagnostics
describe the execution path. Calling all four of them "the RAG result" hides the boundaries you need
to test.

## Scene 2: AI Fabric Separates Retrieval From Generation

**Visual:** Split the flow between `RAGProvider` and the orchestration pipeline.

```text
RAGProvider
  performRag(...)       -> documents + scores
  performRAGQuery(...)  -> documents + context + retrieval metadata

Orchestration pipeline
  retrieved context -> AICoreService -> generation provider -> answer
```

Put a clear **No LLM call here** label inside the default `RAGService` box.

**Narration:**

In AI Fabric, the default `RAGService` is retrieval-only. It generates a query embedding, searches
the configured evidence sources, maps results into `RAGDocument` objects, and builds a context
string. It does not call an LLM.

The method name `performRAGQuery` can sound like it generates an answer, but its current contract is
documents plus context for downstream generation. The orchestration pipeline owns the separate call
to `AICoreService`, using an LLM selected for the `GENERATION` purpose.

That separation is deliberate. You can test retrieval with local embeddings and Lucene without an
LLM. You can test prompt behavior against known context. You can also distinguish a search failure
from a generation failure instead of receiving one opaque provider exception.

## Scene 3: Five Conditions Make A Grounded Answer Possible

**Visual:** Show five readiness switches feeding one RAG request.

```text
1. approved data is indexed
2. embedding and vector providers are compatible and ready
3. a RAGProvider bean is available
4. server policy allows retrieval from an approved vector space
5. generation is enabled and an LLM provider is ready
```

**Narration:**

A dependency on the RAG module is necessary, but it is not sufficient proof of grounded answers.

First, approved source data must have completed indexing. Second, query embeddings and stored vectors
must use a compatible embedding space, and the vector provider must be ready. Third, Spring must have
a `RAGProvider`. The default bean is conditionally created when the RAG module is enabled and its
embedding, vector, and search dependencies exist. An application can replace it with another
implementation of the SPI.

Fourth, the effective orchestration policy must allow retrieval and constrain which vector spaces
may be searched. Fifth, generated wording requires generation to be enabled and a suitable LLM
provider to be available.

These are separate readiness facts. A healthy LLM cannot repair an empty index. Indexed documents
cannot compensate for a missing generation provider. A configured vector space is not automatically
authorized for every mode or caller.

## Scene 4: Intent Requests Work; Server Policy Grants It

**Visual:** Show structured intent fields on the left and server-owned policy on the right.

```text
Intent request                         Effective policy
requiresRetrieval = true       ->      retrievalEnabled
requiresGeneration = true      ->      response-generation budgets
vectorSpace = knowledge-article ->     retrieval vector-space allowlist
optimizedQuery = account access ->     RAG limits and thresholds
```

Show denied branches for **retrieval disabled** and **required allowlist missing**.

**Narration:**

The intent model can identify that a question needs retrieval and generation. It can propose a
vector space and an optimized retrieval query. Those outputs request work; they do not authorize it.

`IntentHandlingStep` compares the request with the effective server policy. If retrieval is disabled,
the pipeline returns a controlled result instead of searching anyway. A strict mode can require a
configured vector-space allowlist and stop when that allowlist is absent.

This is why mode configuration matters. The model decides what the message appears to need. The
server decides which capabilities, spaces, fan-out behavior, document limits, context limits,
similarity threshold, and response budget are permitted.

Grounding is therefore an orchestration decision constrained by application policy, not a prompt
that gives the model unrestricted access to every index.

## Scene 5: Route Retrieval Into Approved Evidence Spaces

**Visual:** Show an intent proposing `knowledge-article`, then validate it through configured spaces
and an allowlist.

```text
model proposal
  -> configured vector-space validation
  -> mode allowlist
  -> max-spaces budget
  -> one approved space or bounded fan-out
```

Show a request with no eligible space ending in clarification rather than search.

**Narration:**

Before retrieval, AI Fabric resolves the vector spaces that are eligible for this request.

A model-proposed space is normalized and checked against configured entity types. A mode allowlist
can restrict retrieval further. If selection is required and several approved domains are possible,
the pipeline can ask which domain to search. If fan-out is allowed, multiple spaces are capped by the
configured maximum and searched as a bounded set. If fan-out is not allowed, the policy suppresses
it.

For the support assistant, the ordinary path can resolve to the `knowledge-article` space. A larger
application might separate policies, troubleshooting articles, and live records. That does not mean
the model should search all of them by default.

The vector-space decision determines the evidence domain. It must remain visible in diagnostics and
must never be treated as authorization merely because the LLM named it.

## Scene 6: Keep User, Retrieval, And Embedding Queries Distinct

**Visual:** Start with one message and branch it into three labeled forms.

```text
User/generation query:
  "How can I recover access to my account?"

Retrieval query:
  processed query used to request evidence

Embedding query:
  retrieval query, optionally enriched with bounded target hints
```

Show bounded history and attachments beside the pipeline, not concatenated into the raw query.

**Narration:**

The wording used to answer the user and the wording optimized for similarity search do not have to
be identical.

The pipeline preserves a generation query that represents the user's request. It prepares a
retrieval query from processed intent data. An embedding query can use the optimized query and, when
explicitly enabled, bounded hints from resolved targets. Metadata records the user query and the
effective embedding query for diagnostics.

This design helps a short follow-up retrieve the right meaning without rewriting the user's question
inside the answer. It also prevents a dangerous shortcut: retrieval queries must come from the
actual processed request, not from a carrier string that mixes arbitrary history and attachments
into one uncontrolled search phrase.

Conversation history, pinned targets, and transient inputs have their own bounded contracts. They
are context, not permission to search unrelated data.

## Scene 7: Retrieval Produces Inspectable Documents

**Visual:** Animate the default single-space retrieval path.

```text
RAGRequest
  query + entityType + limit + threshold + metadata + authContext
    -> query embedding
    -> RAGSearchExecutor
    -> configured search source or vector search
    -> filtered RAGDocument list
```

Show one conceptual document card with `id`, `content`, `score`, `metadata`, and `source`.

**Narration:**

For basic RAG, the orchestrator creates a `RAGRequest` with the retrieval query, selected entity
space, result limit, similarity threshold, request metadata, and canonical caller context.

The default RAG service creates the query embedding and delegates search through
`RAGSearchExecutor`. Search results are mapped into `RAGDocument` objects containing approved
identity, content, score, metadata, vector-space information, and source attribution when available.

The similarity threshold controls which nearby vectors qualify, and the limit controls how many may
be returned. A score ranks evidence for that search operation. It is not a probability that the
document is true, a confidence that the final answer is correct, or proof that the source is allowed
for the caller.

Hybrid and contextual paths are optional capabilities. Diagnostics distinguish whether hybrid
search was requested, supported, and actually used. Do not describe every vector provider as having
the same search behavior.

## Scene 8: Context Is A Bounded Projection Of Evidence

**Visual:** Show six retrieved document cards. Allow four into the generation context, then trim the
context at a character boundary. Keep all client-visible documents in a separate lane.

```text
retrieved documents
  -> maxDocumentsReturnedToClient
  -> maxDocumentsUsedForContext
  -> maxContextChars
  -> model context
```

Render context entries in this conceptual shape:

```text
[vectorSpace=knowledge-article id=<stable-id>]
<approved title>
<approved content>
---
```

**Narration:**

Retrieved documents do not enter the prompt without bounds.

`RagContextSupport` selects at most the policy's context-document limit and truncates the assembled
context to the configured character budget. Its default context representation preserves stable
document IDs and vector-space labels alongside approved title and content.

The number of documents returned to the client and the number used for generation are separate
budgets. You may expose several evidence cards for inspection while grounding the model with a
smaller, higher-ranked subset. More context is not automatically better. Irrelevant or excessive
content can dilute the evidence and make prompt behavior harder to test.

The context is still derived data. It should contain only fields already approved for model
exposure. Context budgeting does not sanitize a field that should never have been indexed.

## Scene 9: Label Different Evidence Types Before Combining Them

**Visual:** Merge three clearly labeled lanes into the generation context.

```text
RETRIEVED DOCUMENTS   -> policies and knowledge
PINNED TARGETS        -> explicit current items or attachments
READ ACTION EVIDENCE  -> current application facts
```

Show **current application facts** taking precedence over a general policy document when they
describe the same live field.

**Narration:**

Indexed documents are not the only evidence the orchestration pipeline can use.

Resolved attachments or pinned targets can be prepended as explicit evidence for the current turn.
A permitted read action can contribute current application facts. AI Fabric keeps these sections
labeled so prompts and diagnostics can preserve their meaning.

Do not flatten them into one untyped paragraph. A policy article can explain why a verified payment
method is required. A read action can state whether the current account actually has one. The policy
must not invent the live status, and the live status must not silently rewrite the policy.

CORE-03 focuses on indexed support evidence, but this ownership rule prepares you for later action
and security lessons: retrieved guidance explains; trusted current facts report state; application
services authorize and execute changes.

## Scene 10: Generation Uses Evidence Through A Managed Prompt

**Visual:** Show the final generation request as two protected inputs.

```text
User question: <generation query>
Relevant context: <bounded evidence context>

Prompt rule: use only the relevant context
```

Then show `AICoreService` routing with `LlmPurpose.GENERATION` to the configured provider.

**Narration:**

Once evidence context exists, `RagResponseGenerationSupport` renders the RAG answer template with the
generation query and bounded context.

The default curated prompt instructs the model to answer using only the relevant context, state when
evidence is insufficient, avoid inventing absent live facts, and avoid exposing internal metadata or
provider details as user-facing prose. An application can supply managed prompt overlays, while the
framework retains the shared RAG template family and required placeholders.

AI Fabric sends the rendered prompt through `AICoreService` with the `GENERATION` purpose. The
effective response profile and server policy bound the generation token budget. Provider routing is
therefore separate from embedding routing.

A strong prompt reduces risk; it does not mathematically guarantee truth. The answer remains model
output and must be evaluated against the evidence that accompanied it.

## Scene 11: Return The Answer And Its Evidence Separately

**Visual:** Show an `OrchestrationResult` split into three API sections.

```text
message / answer
data.documents + data.ragResponse
metadata + request diagnostics
```

Connect each answer claim back to evidence cards in the UI, but do not draw automatic sentence-level
citation links.

**Narration:**

The normal grounded path returns an `INFORMATION_PROVIDED` orchestration result. Its message can hold
the generated answer. Its data includes the retrieved documents, the complete `RAGResponse`, and a
flag showing that generation was required.

The RAG response can expose document IDs, content, scores, entity space, request ID, embedding model,
processing times, search-source diagnostics, and the search path. Generation metadata can expose the
response model, path, and timing.

This lets a UI present a readable answer and a separate evidence panel without asking the model to
reconstruct source records. A document ID proves that the document was retrieved. It does not, by
itself, prove that every sentence in the answer came from that document. If your product promises
citations, define and test the mapping between claims and evidence rather than relabeling a list of
retrieval results as sentence-level citation proof.

## Scene 12: No Evidence Is A Valid Retrieval Outcome

**Visual:** Show a successful search producing zero documents.

```text
search executed successfully
documents = []
context = "No relevant context found."
answer = transparent limitation
```

Contrast it with an unsupported answer crossed out.

**Narration:**

A successful retrieval operation can return zero relevant documents. That is not the same as a
provider outage, and it is not permission to answer from general model knowledge.

The default context builder represents this state as `No relevant context found.` The generation
support uses a dedicated no-context prompt when generation is enabled. That prompt tells the model
to be transparent about missing evidence and not infer unsupported conclusions. If no usable
no-context generation is available, the helper can return a static insufficient-information
statement.

Either presentation must remain visibly ungrounded: the evidence list is empty, and the answer must
not claim a fact that was not retrieved. A polished paragraph is not evidence.

This state is especially valuable in the course lab. An indexed known question should return the
expected article. An unsupported question should show an honest absence. Both outcomes prove more
than a demo that always finds something.

## Scene 13: Distinguish Absence, Retrieval Failure, And Generation Failure

**Visual:** Show a three-row failure matrix.

| State | Evidence | Result signal |
| --- | --- | --- |
| No relevant match | Empty | Successful search with explicit no-context behavior |
| Retrieval unavailable or failed | Empty or partial | Unsuccessful orchestration result or `RAGResponse.success=false`, with diagnostics |
| Generation fails after retrieval | Retained | Orchestration `ERROR` with generation failure details |

**Narration:**

Do not diagnose every empty answer as the same problem.

No relevant match means search ran but no document met the retrieval contract. A missing RAG provider
produces a structured unsuccessful result explaining that the module is unavailable. A search or
embedding exception produces a `RAGResponse` with `success` set to false and an error message.
Multi-source retrieval can also report attempted, succeeded, failed, and skipped source counts and
whether the result was degraded.

If generation throws after evidence has been retrieved, the basic orchestration path returns an
`ERROR`, retains the retrieved documents and RAG response in result data, and records the generation
error. This is different from no evidence.

Your API and UI may translate internal failures into safe user-facing wording, but release evidence
must preserve the distinction. Do not add a canned answer that makes provider failure appear to be a
grounded success.

## Scene 14: Prove Retrieval And Grounding Independently

**Visual:** End with a two-column verification board.

```text
Retrieval proof                    Generation proof
expected document ID returned      answer uses only supported facts
wrong-space document excluded      evidence IDs remain visible
threshold and limits respected     no-evidence answer stays limited
request/source diagnostics present provider failure remains distinguishable
```

Then display the complete flow one final time.

```text
processed question
  -> server-approved vector space
  -> query embedding and retrieval
  -> ranked evidence documents
  -> bounded labeled context
  -> generation provider
  -> answer + evidence + diagnostics
```

**Narration:**

Test the two halves of RAG independently before testing them together.

Retrieval tests should use known indexed evidence and assert stable document identity, approved
metadata, scope, threshold behavior, and the absence of unrelated records. Generation tests should
use controlled context and assert that supported facts appear, unsupported facts do not, evidence
IDs remain available to the caller, and no-evidence behavior stays explicit.

The integrated test then proves the complete vertical slice with the configured providers. It should
also exercise one intentional failure, such as removing the indexed article or disabling the live
generation provider, and verify the correct boundary reports it.

You are done with the theory when you can answer four questions separately: What was searched? What
evidence was retrieved? What context reached the model? What did the model generate? CORE-03 turns
those answers into an executable Spring Boot lab.

## Final Request And Ownership Reference - Do Not Narrate As A List

Use this table to verify all generated diagrams and labels.

| Stage | Responsible owner | Input | Verifiable output |
| --- | --- | --- | --- |
| Intent and mode resolution | AI Fabric orchestration under application configuration | Processed message and trusted context | Retrieval/generation requirements and effective policy |
| Vector-space routing | AI Fabric policy and routing support | Proposed spaces, configured spaces, allowlist, budgets | Approved single space or bounded fan-out |
| Query preparation | AI Fabric orchestration | Processed query, optimized query, bounded target hints | Retrieval query and traceable embedding query |
| Retrieval | `RAGProvider`, embedding provider, search/vector provider | `RAGRequest` | `RAGDocument` list, context, scores, request/source diagnostics |
| Context assembly | AI Fabric orchestration | Retrieved and other approved evidence | Labeled, document-bounded, character-bounded context |
| Generation | `AICoreService` and configured generation provider | Managed prompt, query, approved context | Generated answer and generation trace |
| Domain truth and authorization | Spring Boot application | Authenticated identity and current business state | Allowed evidence and actions; trusted current facts |
| Presentation | Spring Boot API and UI | `OrchestrationResult` | Answer, evidence, and safe diagnostics shown as distinct artifacts |

## Accuracy Guardrails For NotebookLM

- Keep AI Fabric's current basic RAG path as the subject; advanced query expansion and evaluation are
  later production topics.
- State clearly that the default `RAGService` performs retrieval and context building, not LLM
  generation.
- Do not claim that `RAGProvider.performRAGQuery` generates an answer despite its method name.
- Do not imply that an intent-selected vector space bypasses configured entity types, mode
  allowlists, or application authorization.
- Do not treat client-supplied metadata or tenant IDs as authorization.
- Do not claim every vector provider or search source supports hybrid search, contextual search, or
  identical metadata filtering.
- Do not call a similarity score a probability, calibrated confidence, source truth score, or answer
  correctness guarantee.
- Do not claim that every returned document enters the model context; client and context document
  budgets are separate.
- Do not present a retrieved document ID as automatic sentence-level citation or proof that the model
  used every returned document.
- Do not imply that a grounding prompt guarantees factual output. It is a policy instruction whose
  behavior must be tested.
- Distinguish a successful zero-result search from a retrieval provider failure and from a generation
  provider failure.
- Do not describe the default no-context prompt or static insufficient-information statement as
  retrieved evidence.
- Do not imply that policy documents establish current live application state. Live state requires
  trusted application evidence such as an authorized read action.
- Do not invent API endpoints, vector-space names beyond the conceptual course example, record IDs,
  exact scores, prompt output, provider models, timing, or terminal results.
- Do not claim the future learner checkpoint, lab, or generated video has already passed review.
- Do not add performance, accuracy, compliance, or production-readiness claims.
