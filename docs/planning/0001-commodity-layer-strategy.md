# ADR 0001 — Adopt mature libraries for commodity layers; keep the differentiated layer

- **Status:** Accepted, amended by ADR 0004 for vector providers
- **Date:** 2026-06-16
- **Deciders:** maintainers
- **Context version:** AI Fabric `0.2.1`, Java `21`, Spring Boot `4.1.0`, Spring AI `2.0.0`

## Context

A capability review (see `RELEASE_READINESS_AND_CLEANUP_PLAN.md` §9) found that AI Fabric's depth and
differentiation live in its **orchestration / actions / governance** layer, while several
commodity layers were duplicated in-house: vendor LLM/embedding provider calls, custom HTTP/retry
helpers, rate limiting, a bespoke batch/migration engine, and custom "JSON-out-of-an-LLM" parsing.

Many of these commodity layers are exactly what mature, maintained libraries already provide better:
**Spring AI** (`ChatModel`, `EmbeddingModel`, structured output, tool calling, observability),
**LangChain4j**, **Resilience4j**, and **Spring Batch**.

The vector layer was re-evaluated after this ADR. ADR 0004 keeps AI Fabric's native vector
providers for the release path because `VectorDatabaseService` is broader than Spring AI
`VectorStore`: exact fetch, lifecycle update/delete, entity-type clear, scans, counts, diagnostics,
and governance/admin readiness all have to be preserved without sidecar storage.

Crucially, the heavy pieces already sit behind AI Fabric's own SPIs
(`ai.fabric.provider.AIProvider`, `ai.fabric.embedding.EmbeddingProvider`,
`ai.fabric.rag.VectorDatabaseService`), so commodity execution can be replaced or hardened behind
those seams without changing the differentiated layer above them.

## Decision

Stop maintaining commodity LLM/embedding execution in-house. Implement AI Fabric's
`AIProvider` and `EmbeddingProvider` SPIs over Spring AI, and reinvest the freed effort into the
differentiated layer. Do not rewrite the differentiated layer.

Keep the native vector providers for full lifecycle/admin support. Spring AI `VectorStore` may still
be added later as an optional simple-RAG adapter, but it is not the implementation for the current
production `VectorDatabaseService` contract.

### Keep / Replace

- **LLM / embedding providers:** replace hand-rolled HTTP provider execution behind
  `AIProvider`/`EmbeddingProvider` with Spring AI `ChatModel`/`EmbeddingModel` adapters.
- **Cloud vector stores:** keep and harden the native Pinecone, Qdrant, Weaviate, and Milvus
  providers behind `VectorDatabaseService`; there is no Spring AI replacement for the full
  lifecycle/admin contract in this release.
- **Local vector stores:** keep Lucene and memory. Lucene is suitable for local/small deployments;
  memory is dev/test only.
- **Custom HTTP/retry helpers:** replace with Spring `RestClient`/`WebClient` plus Resilience4j.
- **Relay rate limiting/hash helpers:** replace `FixedWindowRateLimiter` and local hashing helpers
  with Resilience4j/Bucket4j and Guava where appropriate.
- **Migration engine:** evaluate Spring Batch; adopt only if the job semantics justify the weight.
- **Structured JSON from LLMs:** partially replace with Spring AI structured output while keeping
  AI Fabric's repair/fallback layer.
- **Cache and events:** keep Caffeine/Spring `CacheManager` and Spring `ApplicationEvent`.
- **Differentiated layer:** keep orchestration, actions/connectors/MCP, governance, relationship
  query, annotation processing, intent extraction, PII, and prompt-injection analysis.

## Prerequisite / hard constraint

The Spring platform upgrade has happened in the current release branch:

- Java `21`
- Spring Boot `4.1.0`
- Spring AI `2.0.0`

Release verification must validate the full reactor and examples on this platform. Do not use
`-DskipTests` or `maven.test.skip` for release verification.

## Consequences

**Positive**
- Delete ~12–15k LOC of the least-tested code; shed its maintenance and risk.
- Gain breadth (more providers/stores), streaming, **native tool calling**, structured output,
  observability, and battle-tested retry — for free, maintained by others.
- Reposition from *competing with* Spring AI on plumbing to *building on it*: "governed orchestration,
  an MCP-capable actions platform, and data governance **on top of** Spring AI." Potential adoption
  channel (Spring AI users add the AI Fabric layer).
- Keep AI Fabric differentiated where the provider contract is broader than the commodity abstraction:
  native vector lifecycle/admin providers remain first-class.

**Negative / risks**
- New hard dependency on the Spring AI ecosystem and its release cadence.
- Migration + re-test effort; reimplement `ProviderStatus`/fallback/bounded-facts at the Spring AI
  provider adapter layer.
- Native vector providers remain AI Fabric-owned code, so they need explicit capability diagnostics,
  shared contracts, and provider-specific mocked/live coverage.
- Spring Batch is heavyweight — adopt only if the migration job semantics justify it.

## Alternatives considered

- **Keep hand-rolling all providers.** Rejected for LLM/embedding execution: highest maintenance,
  weakest tests, no native tool calling, and it pits the project against Spring AI on its strongest
  ground.
- **Replace native vector providers with Spring AI VectorStore.** Rejected for the current release:
  it does not cover AI Fabric's full lifecycle/admin contract without extra sidecar storage or
  provider-specific workarounds.
- **LangChain4j instead of Spring AI.** Viable for the same role (broad model coverage, less
  Spring-opinionated); fits the same SPI-adapter approach. Decision: prefer Spring AI for Spring-Boot
  alignment, but the SPI seam keeps either option open and avoids lock-in.

## Migration plan (phased, low-risk because of the SPIs)

1. **Phase 0 — Spring Boot/Spring AI upgrade:** complete in the current branch; validate the full
   reactor and examples.
2. **Phase 1 — Provider adapter:** `ai-fabric-provider-springai` implementing
   `AIProvider`/`EmbeddingProvider` over `ChatModel`/`EmbeddingModel`; deprecate the hand-rolled
   providers (keep as optional/legacy for one cycle).
3. **Phase 2 — Vector hardening:** keep native vector providers, add explicit capability flags,
   fail-closed metadata filters, shared lifecycle contracts, Testcontainers coverage, and live
   Pinecone verification.
4. **Phase 3 — Cross-cutting:** Resilience4j for retry/rate-limit; evaluate Spring Batch for migration;
   adopt Spring AI structured output, keeping the repair layer.
5. Keep every SPI as the seam throughout, so consumers are insulated and the project stays
   library-agnostic.
