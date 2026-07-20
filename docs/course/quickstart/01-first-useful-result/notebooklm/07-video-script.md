# NotebookLM Video Script: From Application Record To Semantic Evidence

## Production Direction

- Target duration: 6-8 minutes.
- Audience: Java and Spring Boot developers seeing AI Fabric for the first time.
- Voice: direct, practical, calm, and technically precise. Address the developer as **you**.
- Format: architecture explanation before the lab, not a code-along.
- Primary objective: make the indexing and search flow predictable before any implementation begins.
- Visual style: use simple architecture diagrams, short labels, and one conceptual Java entity. Do
  not show invented framework APIs or configuration.

## Scene 1: The First Useful AI Result

**Visual:** A Spring Boot application containing five support articles. One article is titled
"Recover account access." A user asks, "How can I get back into my account?"

**Narration:**

You already know how to store a support article in a Spring Boot application. But saving that article
does not make it available to semantic search. A user may ask a question with different words from
the article, and exact keyword matching may miss it.

In this lesson, your first useful AI Fabric result is not a chatbot response. It is stronger and
easier to prove: a paraphrased question retrieves the expected application-owned article as evidence.

You will build this without an LLM and without an OpenAI key. Local ONNX embeddings and a local
Lucene vector index are enough.

## Scene 2: Separate Three Different Things

**Visual:** Three connected boxes labeled Domain Record, Search Projection, and Vector Evidence.

**Narration:**

To reason about semantic retrieval correctly, keep three things separate.

First, the domain record. Your application owns the support article, its database lifecycle, and its
business rules.

Second, the search projection. You decide which approved text AI Fabric may embed and which
structured values it may store as metadata.

Third, the vector evidence. The configured embedding and vector providers turn that projection into
something a semantic query can retrieve.

A database row can exist while its vector is missing, stale, or stored in the wrong vector space.
Database persistence and retrieval readiness are not the same fact.

## Scene 3: Describe The AI-Facing Shape

**Visual:** Show this conceptual Java shape. Highlight each annotation as it is explained.

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

**Narration:**

AI Fabric lets you describe the approved AI-facing shape of an entity.

`AICapable` identifies the entity type and connects it to AI Fabric configuration.

`AISearchable` marks text that will contribute to the embedding and semantic search. Here, the title
and body carry the article's meaning.

`AIContext` marks structured metadata that should travel with the vector but should not be embedded.
The stable ID connects evidence back to the source record, while category remains a structured value.

Configuration in `ai-entity-config.yml` can override annotation defaults. The order is configuration,
then annotation defaults, then framework defaults.

These declarations describe intended behavior. They do not prove that an indexing operation has run.

## Scene 4: Follow The Indexing Path

**Visual:** Animate the following path from left to right.

```text
KnowledgeArticle
  -> approved content and metadata
  -> AI Fabric embedding contract
  -> local ONNX vector
  -> vector + ID + content + metadata
  -> Lucene index
```

**Narration:**

When you index an article, your application first supplies approved searchable content and metadata.
AI Fabric creates an embedding request through its provider contract. The ONNX provider converts the
text into a numeric vector locally. AI Fabric then stores that vector together with the stable entity
ID, projected content, and metadata through Lucene.

The vector carries semantic meaning. The ID preserves identity. The content lets you inspect the
evidence. The metadata preserves structured context without forcing categories or identifiers into
the embedding.

The embedding model also defines a vector dimension. The content vector and future query vector must
use compatible models and dimensions. Otherwise they do not belong in the same similarity space.

## Scene 5: Follow The Search Path

**Visual:** Reverse the direction from a question into the same ONNX and Lucene components, then show
an evidence response.

```text
Question
  -> query embedding
  -> Lucene similarity search
  -> entity ID + score + content + metadata
```

**Narration:**

Now the user asks, "How can I get back into my account?"

AI Fabric sends the question through the same compatible embedding provider. ONNX creates a query
vector, and Lucene finds nearby vectors. AI Fabric returns evidence with entity identity, similarity
score, approved content, and metadata. Your application decides how to expose that evidence through
its API.

The similarity score helps rank results. It is not a calibrated probability and should not be shown
as guaranteed confidence. Result limits and thresholds control which nearby vectors are returned;
they cannot create evidence that was never indexed.

## Scene 6: Know Who Owns Each Decision

**Visual:** Four columns: Your Application, AI Fabric, ONNX, and Lucene. Add Browser beneath them as a
presentation-only boundary.

**Narration:**

Your application owns the support-article lifecycle, approved fields, stable IDs, metadata, indexing
triggers, and API response.

AI Fabric owns the entity-processing, embedding, vector, indexing, and search contracts that connect
those application decisions to providers.

ONNX owns local embedding inference. Lucene owns vector persistence and similarity lookup.

The browser owns request input and presentation. It must not manufacture retrieval intelligence with
keyword shortcuts or canned answers.

This ownership map tells you where to investigate when a result is missing.

## Scene 7: Make The Failure Visible

**Visual:** Show five database records, zero vectors, and an empty evidence response. Then run an
Index operation and show five vectors followed by the expected article result.

**Narration:**

The lab deliberately searches at the wrong time: after you seed five support articles, but before you
index them.

The correct response is no evidence. The request may succeed, but Lucene has no matching vectors to
return. This is not a reason to add a generic answer, browser keyword matching, or an LLM fallback.

Readiness should make the mismatch visible: five source records and zero indexed vectors.

After you run the explicit indexing operation, repeat the same paraphrased query. The expected
article ID, score, approved content, metadata, and vector count become your proof that retrieval works.

## Scene 8: Retrieval Is Not RAG

**Visual:** Show two flows. Keep the first highlighted.

```text
Semantic retrieval: question -> evidence
RAG: question -> evidence -> LLM -> grounded answer
```

**Narration:**

This first lesson stops at evidence. That is semantic retrieval.

RAG adds another step later: retrieved evidence is supplied to a language model, which composes a
grounded answer. If you add generation too early, fluent wording can hide weak or missing retrieval.

By proving retrieval first, you know exactly which application record supports a result before an LLM
changes how that result is expressed.

## Scene 9: Handoff To The Lab

**Visual:** Return to the five support articles and the user's paraphrased question. Display the final
question on screen.

**Narration:**

You now have the mental model for your first AI Fabric workflow. Your application owns the knowledge.
AI Fabric coordinates the contracts. ONNX creates compatible vectors. Lucene stores and retrieves
the evidence. The browser displays what the backend actually returned.

Before you continue, predict the intentional failure:

You seeded five support articles but have not indexed them. What should semantic search return, and
why?

Carry your answer into the lesson and knowledge check.

## Accuracy Guardrails For NotebookLM

- Do not call database records vector evidence before indexing.
- Do not imply an LLM is used in QS-01.
- Do not invent method signatures, endpoints, properties, or executable output.
- Do not describe a similarity score as probability or guaranteed confidence.
- Do not claim the starter, solution, or video has already been published.
- Do not introduce keyword or canned-answer fallbacks.
- Do not add performance, accuracy, compliance, or production-readiness claims.
