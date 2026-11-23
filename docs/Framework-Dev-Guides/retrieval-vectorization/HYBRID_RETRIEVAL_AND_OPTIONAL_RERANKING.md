# Shared Retrieval Profiles And Optional Reranking

Available in the 0.8.67 source candidate. Confirm immutable publication before
using these coordinates. Existing unprofiled applications retain their prior
search behavior; neither hybrid retrieval nor learned reranking is enabled by
adding a dependency alone.

## One Retrieval Path

Use `ai.infrastructure.rag.retrieval-profile` for an explicit bounded profile:

```yaml
ai:
  infrastructure:
    rag:
      retrieval-profile:
        mode: HYBRID
        vector-candidates: 20
        lexical-candidates: 20
        max-candidates: 40
        max-results: 6
        max-context-characters: 8000
        rank-constant: 60
        failure-policy: FAIL
```

`DENSE` uses meaning search, `HYBRID` independently retrieves both dense and
lexical candidates, and `KEYWORD` is the diagnostic lexical-only mode. Candidate
allowances are shared across the selected eligible sources, not multiplied by
the number of namespaces. One framework-owned RRF stage combines the lists.
Final context selection preserves attribution and reports truncation.

The shared deadline is checked before and after each retrieval leg; late native
results are not accepted and cannot start another leg or reranking. Source
adapters still own finite native transport timeouts: this synchronous contract
does not forcibly preempt a blocked third-party SDK call.

Keyword-only retrieval does not request an embedding or call the dense service.
Its metadata states `embeddingUsed:false`; no provider/model receipt is invented.

Inspect `retrievalCapabilities` for the actual corpus before claiming lexical
readiness. Weaviate uses native BM25F over its searchable content field. A dense-
only adapter or absent text index does not become hybrid because a flag was set.
Milvus native full-text support is not added by this candidate.

`VISIBLE_DENSE_FALLBACK` allows a visible dense result when lexical retrieval is
unavailable. Authorization, scope and funding failures are not availability
failures. Inspect effective mode, per-source diagnostics and score type rather
than presenting BM25/RRF scores as cosine confidence or a match percentage.

## Optional Cohere Adapter

Import the BOM and add `io.github.loom-ai-labs:ai-fabric-reranker-cohere` alongside
the RAG module. The host supplies a provider bean with its own secret loader and
accounting policy:

```java
RerankingProvider createReranker(Supplier<String> ownedKey, RerankingCallBudget accounting) {
    return new CohereRerankingProvider(ownedKey, "rerank-v5.0-fast", accounting);
}
```

Register that provider in the application's normal Spring configuration, then
explicitly configure the same provider/model:

```yaml
ai:
  infrastructure:
    rag:
      reranking:
        enabled: true
        provider: cohere
        model: rerank-v5.0-fast
        max-candidates: 40
        max-characters-per-candidate: 4000
        max-total-characters: 160000
        max-tokens-per-document: 1024
        timeout-ms: 5000
        failure-policy: FAIL
```

The host accounting bridge must admit an attempt before dispatch, settle actual
native billed units and retain conservative holds when outcomes are unknown.
The framework does not create financial approval, assume unknown cost is zero,
or use another provider/key after admission fails. There is no no-op accounting
bridge supplied by the adapter. The native adapter makes one bounded request,
without redirects, automatic retries or customer-selected destination URLs.

Only bounded host-authorized candidate excerpts and the question are submitted
to Cohere. Obtain the application's necessary external-processing approval and
keep credentials outside prompts/configuration-assistant context. Results refer
to existing input IDs; original evidence and provenance are retained. Unknown
or duplicate result references and malformed usage fail instead of injecting
provider-supplied evidence.

`VISIBLE_PRE_RERANK_FALLBACK` may retain the authorized pre-rerank order only on
typed transient availability failure. Diagnostics then state that reranking was
not used; funding, credentials and invalid receipts remain failures. An unknown
native outcome can still incur a held cost when this fallback is selected.

## Qualification

For an unreranked baseline, set `RAGRequest.rerankingEnabled(false)` (also
supported by Advanced RAG). This only opts out for that request; it cannot
enable a host-disabled provider, create consent, change funding, or change the
saved profile. Diagnostics record `REQUEST_DISABLED`. Keep all other experiment
inputs/bounds and source authorization identical when comparing results.

Compare reviewed questions against the same active corpus, generation policy,
candidate/context allowances and evaluation engine. Record both retrieval recall
and final answer quality, including identifiers, paraphrases, mixed sources,
missing facts and denied sources. A reranker cannot recover a missing candidate.
Do not promote defaults from a successful adapter call alone.
