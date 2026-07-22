# NotebookLM Video Script: RAG Quality And Prompt Regression

## Production Instruction

Produce a ten-minute technical video for Java and Spring Boot developers. Use only this script.
Keep AI Fabric and the continuing Support Knowledge Assistant central. Explain the architecture
before the lab, distinguish deterministic release gates from optional model evaluation, and never
present fluent model prose as proof of retrieval quality.

## Opening

A RAG response can sound convincing while using the wrong document, stale content, another
tenant's evidence, or no evidence at all. That is why production RAG quality cannot begin with the
question, "Did the answer sound good?"

In AI Fabric, the release-blocking contract starts earlier. The application defines which evidence
should be retrievable, which evidence must never appear, which current source fragments must be
present, and which stale fragments must be absent. Model-generated wording and model-based
evaluation are useful observations only after that deterministic contract passes.

## The Quality Pipeline

Describe this diagram:

```text
application source rows
       |
       v
AI Fabric projection and vector evidence
       |
       v
verified identity -> tenant and access filters
       |
       v
retrieved evidence IDs, scores, content, and metadata
       |
       v
deterministic quality decision
       |
       +----> optional answer generation
       |
       +----> optional Spring AI relevancy or fact-check evaluation
```

The deterministic decision is the required gate. Optional LLM generation and evaluator calls do
not repair missing, stale, forbidden, or cross-tenant evidence.

## Golden Questions Are Application Contracts

The Support Knowledge Assistant owns a catalog of realistic questions. A case can declare:

- a stable case identifier;
- the authenticated tenant under which retrieval runs;
- expected evidence IDs;
- forbidden evidence IDs;
- required fragments proving the latest source content;
- forbidden fragments identifying stale content;
- whether no evidence is the expected result.

Do not store one expected answer paragraph and compare generated text character by character.
Different models and prompt revisions can express the same grounded answer differently. Stable
evidence identity and current source content are stronger release contracts.

## Evaluate Retrieval Before Generation

For each golden case, run the same evidence service used by the application. Capture observed IDs
and content, then calculate structured failures such as:

```text
EXPECTED_EVIDENCE_MISSING
FORBIDDEN_EVIDENCE_RETURNED
REQUIRED_CONTENT_MISSING
STALE_CONTENT_RETURNED
UNEXPECTED_EVIDENCE_RETURNED
```

A failed quality case should still return a usable diagnostic result. It should not become a vague
server error, and it should not invoke an LLM to explain away the failure.

## Empty Index Versus Insufficient Context

These states are different.

An empty index can be expected before migration or after an intentional reset. A quality case that
explicitly expects no evidence should pass only when retrieval returns none.

Insufficient context occurs when the index is healthy but the expected source is absent. For
example, asking about an audit-retention policy when no approved audit-retention article exists
must produce a failed quality result. A general model answer about common retention practices would
hide the missing application evidence and must not count as success.

## Stale Evidence Detection

Suppose the billing article changes from "replace a payment method" to "download an invoice."
Data Sync updates the source projection under the same stable ID. The quality case then requires the
new phrase and forbids the old phrase.

If the new phrase is missing, synchronization did not reach the vector provider. If the old phrase
is still returned, stale derived evidence remains. Both failures are actionable and independent of
the quality of generated prose.

## Tenant And Access Safety

Run equivalent questions for two tenants whose articles have distinct stable IDs. The authenticated
principal determines tenant scope. The request does not submit a trusted tenant field.

A passing case proves the expected tenant document is present and every cross-tenant or restricted
ID is absent. Similarity score alone never authorizes evidence. Retrieval filters and application
post-hit access checks remain enforcement boundaries.

## Prompt Regression Without Freezing Prose

Prompt resources also need deterministic checks. AI Fabric's prompt resolver combines a base
version with ordered overlays. Tests should prove:

- the intended bundle and overlay resolve;
- omitted prompt keys inherit from the base bundle;
- required template slots such as `{{query}}` and `{{context}}` remain;
- diagnostics identify prompt versions without returning full prompt bodies;
- prompt changes do not replace tenant, action, privacy, or confirmation controls.

Prompts guide model interpretation. They are not authorization policy.

## Optional Spring AI Evaluation

AI Fabric exposes a Spring AI evaluation facade for relevancy and optional fact checking. The
facade converts bounded AI Fabric RAG documents into Spring AI evaluation requests. It limits the
number and size of documents, removes sensitive metadata keys such as credentials, tokens, URLs,
paths, prompts, and embeddings, and fails when no RAG documents are available.

This evaluator is valuable for model-specific observations, but it is optional. Its result should
be retained separately from deterministic evidence tests because it can vary by evaluator model,
provider availability, prompt, and cost.

## Visible Failure

If retrieval fails, generation is skipped and the application returns a safe retrieval failure. If
generation fails or produces invalid citations, the application returns a visible generation
failure. It does not substitute canned advice. In a keyless runtime where generation is disabled,
calling the generation endpoint should report that state rather than pretend an LLM answered.

## Incorrect Architecture

An incorrect release test asks five questions, reads the generated answers, and lets a developer
decide that they look reasonable. This misses tenant leakage, stale vectors, accidental prompt
fallback, and provider failures. Another incorrect design lets an LLM evaluator pass a case even
when the expected evidence ID is absent.

The correct design gates deterministic retrieval and prompt contracts first, then records optional
model observations separately.

## Lab Bridge

In PROD-06, you will implement golden evidence cases for both support tenants, test empty-index and
insufficient-context behavior, detect stale synchronized content, verify prompt resolution, and run
the full suite with real local ONNX embeddings and Lucene. An optional OpenAI exercise comes only
after the keyless gate passes and writes separately named evidence.

## Closing

Production RAG quality is not confidence in a paragraph. It is evidence identity, freshness,
access safety, prompt structure, and visible failure. AI Fabric gives the application a stable place
to enforce and test those contracts while keeping model-based evaluation useful but honest.
