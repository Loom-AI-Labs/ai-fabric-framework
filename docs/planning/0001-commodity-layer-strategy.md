# ADR 0001 — Adopt mature libraries for commodity layers; keep the differentiated layer

- **Status:** Proposed
- **Date:** 2026-06-16
- **Deciders:** maintainers
- **Context version:** AI Fabric `0.2.1`, Spring Boot `3.2.0`

## Context

A capability review (see `RELEASE_READINESS_AND_CLEANUP_PLAN.md` §9) found that AI Fabric's depth and
differentiation live in its **orchestration / actions / governance** layer, while its **least-tested,
most duplicated** code is the commodity infrastructure it hand-rolls: five vendor HTTP provider
clients, six vector-store integrations, a custom HTTP abstraction, hand-written retry, a DIY rate
limiter, a bespoke batch/migration engine, and custom "JSON-out-of-an-LLM" parsing.

These commodity layers are exactly what mature, maintained libraries already provide better:
**Spring AI** (`ChatModel`, `EmbeddingModel`, `VectorStore`, structured output, tool calling,
observability — GA since 2025), **LangChain4j**, **Resilience4j**, **Spring Batch**.

Crucially, the heavy commodity pieces already sit behind AI Fabric's own SPIs
(`ai.fabric.provider.AIProvider`, `ai.fabric.embedding.EmbeddingProvider`,
`ai.fabric.rag.VectorDatabaseService`), so they can be replaced by **adapters** with no change to the
differentiated layer above them.

## Decision

Stop maintaining commodity infrastructure in-house. Implement AI Fabric's existing SPIs as **thin
adapters over mature libraries**, and reinvest the freed effort into the differentiated layer. Do not
rewrite the differentiated layer.

### Keep / Replace

| Area | Today | Decision | Replacement |
|------|-------|----------|-------------|
| LLM / embedding providers (≈5k LOC, lightly tested, no native tool-calling) | Hand-rolled HTTP per vendor | **Replace** behind `AIProvider`/`EmbeddingProvider` | Spring AI `ChatModel`/`EmbeddingModel` (or LangChain4j) |
| Cloud vector stores: qdrant/pinecone/weaviate/milvus (≈6.8k LOC, weakest tests) | Wrappers over official gRPC/clients | **Replace** behind `VectorDatabaseService` | Spring AI `VectorStore` |
| Local vector stores: lucene/memory | Own impl | **Keep** (or back with Spring AI `SimpleVectorStore`) | — |
| `ai.fabric.http` (9 files) + per-provider retry | Custom HTTP + `exchangeWithRetry` | **Replace** | Spring `RestClient`/`WebClient` + Resilience4j |
| `relay` rate limiting (`FixedWindowRateLimiter`, `Hashing`) | DIY | **Replace** | Resilience4j RateLimiter / Bucket4j + Guava `Hashing` |
| Migration engine (pause/resume/progress; 0 Spring Batch) | Hand-rolled batch | **Evaluate → likely Replace** | Spring Batch |
| `llm.structured` / `StructuredJsonExtractor` (10 files) | Custom typed-JSON-from-LLM | **Partial replace** — keep repair/fallback | Spring AI structured output (`BeanOutputConverter`) |
| `ai.fabric.cache` | Caffeine + Spring `CacheManager` | **Keep** — already standard | — |
| `ai.fabric.event` | Spring `ApplicationEvent` | **Keep** — standard mechanism, domain content | — |
| Orchestration pipeline, actions/connector/**MCP**/interception, governance (GDPR/retention/compliance/catalog), relationship-query (NL→JPQL), annotation model + processors, intent extraction, PII / prompt-injection analysis | Own impl | **Keep — this is the moat** | — |

## Prerequisite / hard constraint

**Spring AI 1.0 GA requires Spring Boot 3.4.x; AI Fabric is on 3.2.0.** Therefore the provider/vector
replacements are **gated on a Spring Boot 3.2 → 3.4 upgrade** (or, as an interim, a Boot-3.2-compatible
pre-GA Spring AI milestone — not recommended for a release). The Boot upgrade should be treated as the
first phase and validated independently (it touches the whole reactor and the examples, which use
`spring-boot-starter-parent:3.2.0`).

## Consequences

**Positive**
- Delete ~12–15k LOC of the least-tested code; shed its maintenance and risk.
- Gain breadth (more providers/stores), streaming, **native tool calling**, structured output,
  observability, and battle-tested retry — for free, maintained by others.
- Reposition from *competing with* Spring AI on plumbing to *building on it*: "governed orchestration,
  an MCP-capable actions platform, and data governance **on top of** Spring AI." Potential adoption
  channel (Spring AI users add the AI Fabric layer).

**Negative / risks**
- New hard dependency on the Spring AI ecosystem and its release cadence.
- **Requires a Spring Boot 3.4 upgrade first** (non-trivial; affects the whole reactor + examples).
- Migration + re-test effort; reimplement `ProviderStatus`/fallback/bounded-facts and the broad
  `VectorDatabaseService` surface (~20 methods) at the adapter layer.
- Spring Batch is heavyweight — adopt only if the migration job semantics justify it.

## Alternatives considered

- **Keep hand-rolling.** Rejected: highest maintenance, weakest tests, no native tool calling, and it
  pits the project against Spring AI on its strongest ground.
- **LangChain4j instead of Spring AI.** Viable for the same role (broad model coverage, less
  Spring-opinionated); fits the same SPI-adapter approach. Decision: prefer Spring AI for Spring-Boot
  alignment, but the SPI seam keeps either option open and avoids lock-in.

## Migration plan (phased, low-risk because of the SPIs)

1. **Phase 0 — Spring Boot 3.4 upgrade** (prerequisite; validate the full reactor + examples).
2. **Phase 1 — Vector adapter:** `ai-fabric-vector-springai` implementing `VectorDatabaseService` over
   Spring AI `VectorStore`; prove against an example; then deprecate the cloud vector modules.
3. **Phase 2 — Provider adapter:** `ai-fabric-provider-springai` implementing
   `AIProvider`/`EmbeddingProvider` over `ChatModel`/`EmbeddingModel`; deprecate the hand-rolled
   providers (keep as optional/legacy for one cycle).
4. **Phase 3 — Cross-cutting:** Resilience4j for retry/rate-limit; evaluate Spring Batch for migration;
   adopt Spring AI structured output, keeping the repair layer.
5. Keep every SPI as the seam throughout, so consumers are insulated and the project stays
   library-agnostic.
