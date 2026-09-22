# AI Fabric Market And AI Tools Next Steps

- **Status:** Strategic product and technical roadmap recommendation
- **Date:** 2026-09-22
- **Framework baseline:** AI Fabric `0.8.4`, repository commit `0ccbf43e`
- **Audience:** AI Fabric framework, LoomAI platform, product, documentation, and security teams
- **Purpose:** Reconcile the existing market and agentic-tool analysis with the framework that has actually shipped, then define what AI Fabric should support next
- **Scope:** Market position, current capability truth, ecosystem comparison, prioritized capabilities, delivery gates, and explicit deferrals
- **Out of scope:** Implementing the recommendations or defining a commercial pricing model

## 1. Executive Verdict

AI Fabric is no longer an early Java AI integration library. Version `0.8.4` is a governed AI
enablement runtime with:

- annotation-driven live-data synchronization;
- governed document ingestion and deletion;
- tenant- and policy-scoped RAG;
- typed actions, confirmation, review, receipts, and reconciliation;
- backend-owned conversation memory;
- versioned specialists and durable execution;
- bounded sequential, parallel, adaptive, delegation, and handoff chains;
- outbound MCP action execution;
- PII controls and behavior intelligence; and
- purpose-specific generation and embedding provider routing.

The next release should not introduce a second agent engine or an unrestricted workflow graph. The
highest-value direction is to make the existing runtime easier to **prove, observe, expose, scale,
and adopt**.

The recommended release theme is:

> **AI Fabric 0.9: Operational Trust And Interoperability**

Its technical priorities should be:

1. unified observability and evaluation;
2. governed inbound MCP exposure;
3. progressive discovery inside an already-authorized capability catalogue; and
4. typed execution lifecycle streaming.

Its product priority should be one independent design partner using one bounded product template.
That external proof is now worth more than another broad wave of unrelated framework features.

## 2. Documents Reviewed

This verdict incorporates the following existing material:

- the external `MARKET_POSITION_EVALUATION.md` market review;
- [Agentic Framework Compliance Analysis](../Framework-Dev-Guides/architecture/framework/AGENTIC_FRAMEWORK_COMPLIANCE_ANALYSIS.md);
- [AI Fabric Agentic Product Verdict And Delivery Strategy](ai-fabric-flow-architecture-analysis-pack/AI_FABRIC_AGENTIC_PRODUCT_VERDICT_AND_DELIVERY_STRATEGY.md);
- [Specialist-Defined Agentic Enablement Proposal](ai-fabric-flow-architecture-analysis-pack/Full-Proposal/Product-evolution-proposal.md);
- [LoomAI AI Enablement Product And Deployment Template Proposal](0018-loomai-ai-enablement-product-and-deployment-template-proposal.md);
- release notes from [`0.5.0`](../release-notes/0.5.0.md) through [`0.8.4`](../release-notes/0.8.4.md); and
- the current public capability description in the repository [README](../../README.md).

The older documents remain useful as history, but several conclusions describe a framework that no
longer exists. Current planning must use shipped code and verified runtime behavior as its source of
truth.

## 3. What The Market Review Still Gets Right

The market review contains several enduring strategic decisions.

### 3.1 The Java And Spring Boot Wedge Is Credible

AI Fabric is most useful where an existing Spring Boot application already owns users, permissions,
transactions, policies, and business data. The framework adds AI capabilities around those existing
boundaries instead of asking a model, separate agent service, or new data platform to replace them.

### 3.2 Annotation-Driven Enablement Is Differentiated

The combination of annotated domain data, synchronization, approved vector projections, application
actions, trusted context, and governed execution is more defensible than provider abstraction by
itself. Model and embedding APIs are commodities; connecting reasoning safely to live application
state is not.

### 3.3 AI Fabric And LoomAI Need Different Product Boundaries

The correct division remains:

| Layer | Responsibility |
| --- | --- |
| Spring AI and model SDKs | Commodity provider, model, tool, MCP, ETL, and observation integration |
| AI Fabric | Application runtime, evidence, capabilities, governance, specialists, execution, and safe projection |
| LoomAI | Authoring, templates, deployment, secrets, environment binding, operations, rollout, and lifecycle |
| Host application | Authentication, domain truth, authorization, transactions, and final reconciliation |

AI Fabric should not absorb the LoomAI control plane. LoomAI should not recreate AI Fabric's runtime
authority and execution contracts.

### 3.4 A Vertical Proof Beats A Generic Agent Builder

The framework should keep proving named application outcomes such as account resolution, deployment
knowledge, incident investigation, live-data search, policy assistance, and human-reviewed
operations. It should not compete as a generic chatbot builder, prompt dashboard, or unrestricted
agent-graph designer.

### 3.5 External Adoption Is Now The Main Product Risk

The framework has enough breadth. The largest unanswered questions are onboarding effort, API
clarity, operational reliability, and whether an independent team can obtain business value without
maintainer intervention. One external design partner would test those assumptions more effectively
than several more internal demos.

## 4. Corrections Required In Existing Analysis

### 4.1 Unsupported Market Claims

The following kinds of claims should not appear in public material without a reproducible method and
evidence:

- fixed savings such as `$0` versus large monthly provider bills;
- fixed delivery comparisons such as minutes versus months;
- uptime, accuracy, conversion, or cost-reduction percentages;
- "most complete," "only enterprise-grade," or equivalent market-superiority language; and
- legal compliance claims such as "HIPAA compliant" or "GDPR compliant."

The accurate wording is that AI Fabric provides production-oriented controls that applications can
use when implementing security, privacy, audit, and regulated workflows. Legal compliance belongs
to the complete deployed system and its operating organization.

### 4.2 The Agentic Score Is Not Defensible

The `90/100` score in the old compliance analysis has no stable rubric, weighted evidence, test
results, or comparable-framework measurement. It should be archived as historical analysis and
replaced with a versioned capability matrix using the states:

```text
SUPPORTED
PARTIAL
PLATFORM_OWNED
EXPERIMENTAL
DEFERRED
NOT_PLANNED
```

Every `SUPPORTED` claim should link to a public contract, test, real application, and release version.

### 4.3 Several AI Terms Were Applied Incorrectly

- Multi-step intent extraction is not automatically ReAct.
- Progressive fallback parsing is not chain-of-thought reasoning.
- Structured-output repair is not model self-reflection.
- A deterministic orchestration pipeline is not an autonomous plan-and-execute agent.
- Intent history is an audit/history capability, not semantic long-term memory.
- Registered action discovery is not progressive tool discovery at large catalogue scale.

These corrections do not weaken AI Fabric. They make its real capabilities easier to trust.

### 4.4 Previously Missing Capabilities Have Shipped

The old analysis describes MCP and explicit multi-agent coordination as absent. That is obsolete.
AI Fabric now has governed outbound MCP execution, versioned specialists, durable jobs, human review,
declarative bounded chains, parallel workers, adaptive routing, delegation, and handoff. The current
gap is interoperability and operational proof, not the absence of agentic execution.

## 5. Verified `0.8.4` Capability Position

| Area | Current verified position | Important remaining gap |
| --- | --- | --- |
| Live application data | Annotation lifecycle, `AIIndexDocument`, transaction-aware indexing, migration, update, and delete | Unified quality and operational scorecard |
| Document knowledge | Spring AI ETL adapter, deterministic manifests and IDs, exact lifecycle deletion | Broader format support only when a real corpus requires it |
| RAG | Scoped retrieval, metadata policy, evidence, native vector lifecycle adapters, evaluation service | Evaluation beyond answer relevancy and stronger citation contracts |
| Actions | Typed metadata, effective capabilities, confirmation, review, receipts, reconciliation | Progressive discovery when authorized catalogues become large |
| Specialists | Exact versions, manifests, typed schemas, trusted context, waits, durable execution | Standard remote-agent interoperability |
| Chains | Sequential, parallel, adaptive, delegation, handoff, replay, cancellation, bounded manifests | Better operational visualization, not unrestricted graphs |
| MCP | Spring AI-backed outbound MCP action execution with connector dispatch | General governed inbound MCP export layer |
| Conversation state | Backend-owned sessions, store SPI, JPA support, sliding-window context | No built-in summarized or semantic long-term memory found |
| Privacy and tenancy | PII controls, trusted identity metadata, tenant and deployment filtering, fail-closed paths | Continuous leakage and policy-regression evaluation |
| Observability | Provider observations, component metrics, indexing correlation, specialist and chain traces | One end-to-end trace topology and consumable operational dashboard |
| UI integration | Structured orchestration results and reusable AI Fabric Chat UI | Typed incremental lifecycle stream |

Representative code evidence:

- [AIActionToolCallbackFactory](../../ai-infrastructure-module/ai-fabric-core/src/main/java/ai/fabric/intent/action/tool/AIActionToolCallbackFactory.java) filters tools through effective capabilities.
- [SpringAiMcpActionExecutor](../../ai-infrastructure-module/ai-fabric-actions-connector/src/main/java/ai/fabric/intent/action/connector/springai/SpringAiMcpActionExecutor.java) executes outbound MCP tools through Spring AI clients.
- [SpringAiRagEvaluationService](../../ai-infrastructure-module/ai-fabric-rag/src/main/java/ai/fabric/rag/evaluation/springai/SpringAiRagEvaluationService.java) provides an initial RAG evaluation bridge.
- [SlidingWindowMemoryStrategy](../../ai-infrastructure-module/ai-fabric-chat-session/src/main/java/ai/fabric/chat/strategy/SlidingWindowMemoryStrategy.java) implements current conversation-window behavior.
- [MicrometerSpecialistChainMetrics](../../ai-infrastructure-module/ai-fabric-execution/src/main/java/ai/fabric/execution/chain/MicrometerSpecialistChainMetrics.java) records specialist-chain metrics.
- [IndexingMetrics](../../ai-infrastructure-module/ai-fabric-indexing/src/main/java/ai/fabric/indexing/observability/IndexingMetrics.java) records indexing counters and duration.

## 6. Current AI Tool Landscape And AI Fabric's Place

### 6.1 Spring AI Is Infrastructure, Not The Product To Duplicate

Spring AI `2.0.x` now provides first-class recursive tool calling, blocking and streaming execution,
MCP client and server starters, progressive tool disclosure, observations, evaluation utilities,
chat-memory repositories, and document ETL.

AI Fabric should use these facilities underneath its contracts where they fit. It should continue to
own what Spring AI does not own for an application:

- trusted identity and tenant construction;
- effective capability intersection;
- evidence and vector-space policy;
- confirmation and human review;
- durable receipts, replay, and reconciliation;
- exact specialist and chain versions;
- application-safe result projection; and
- cross-provider behavioral guarantees.

The repository currently pins Spring AI `2.0.0`, while the current official reference documents
Spring AI `2.0.1`. A patch upgrade should be evaluated through the complete provider, tool, MCP,
structured-output, and real-application matrix. It is maintenance work, not a new product feature.

### 6.2 LangChain4j Is A Useful Comparison, Not A Required Dependency

LangChain4j provides AI services, tools, RAG, memory, MCP, guardrails, and an experimental agentic
module with sequential, loop, parallel, and conditional patterns. AI Fabric should not copy every
pattern merely for feature-count parity.

AI Fabric's stronger position is bounded execution attached to application authority: durable work,
tenant scope, confirmation, review, receipts, reconciliation, and exact versions. LangChain4j may be
evaluated later as an optional infrastructure adapter only if a concrete provider or customer need
cannot be met through the current contracts and Spring AI.

### 6.3 MCP Is The Immediate Interoperability Priority

MCP is appropriate for exposing and consuming tools, resources, and prompts. AI Fabric already
consumes MCP tools. The next useful boundary is an inbound server that lets Claude Code, other MCP
clients, or LoomAI invoke approved AI Fabric capabilities without bypassing governance.

The MCP specification continues to evolve. AI Fabric should rely on Spring AI's maintained protocol
implementation and maintain a compatibility matrix instead of implementing transport details itself.

### 6.4 A2A Solves A Different Problem

Agent2Agent is useful when an independently deployed, potentially opaque remote agent must advertise
capabilities and exchange messages, tasks, status updates, artifacts, cancellation, and streaming
results. It should not replace AI Fabric's internal specialist calls or bounded chains.

A2A belongs in an optional adapter after LoomAI has a concrete remote-agent integration. A remote
Agent Card may describe capability, but it must never grant AI Fabric authority.

### 6.5 AG-UI Is A Channel Adapter

AG-UI defines an event-based runtime-to-frontend stream for messages, tools, state, and human
interaction. It could be a useful adapter for AI Fabric Chat UI and LoomAI after AI Fabric defines its
own stable typed execution-event contract. It should not become the framework's internal execution
model.

### 6.6 OpenTelemetry Is The Best Shared Observation Vocabulary

OpenTelemetry now defines GenAI semantic conventions for providers, agents, conversations, tools,
retrieval, and workflows. AI Fabric should map its stable concepts onto those conventions where
possible and add AI Fabric-specific low-cardinality attributes only where required.

Prompt text, tool arguments, retrieved evidence, tenant IDs, user IDs, and private results must not
be exported by default. Protected identifiers should be omitted, safely hashed, or stored in a
separately authorized audit system.

## 7. P0: Build Next

### 7.1 Unified Observability And Evaluation

Create one stable observation model spanning:

```text
request
  -> mode and intent
  -> provider/model selection
  -> authorized capability resolution
  -> retrieval and evidence
  -> tool/action/MCP invocation
  -> specialist or chain execution
  -> confirmation or review
  -> receipt and reconciliation
  -> final safe projection
```

Required deliverables:

- stable trace and correlation continuity across every boundary;
- Micrometer observations mapped to OpenTelemetry GenAI conventions where appropriate;
- content capture disabled by default;
- latency, model usage, tool-call count, retrieval count, chain branch, wait, retry, denial, and failure metrics;
- one reference dashboard owned by LoomAI or a reference deployment, not by core framework code;
- golden scenario datasets for RAG, intents, actions, specialists, chains, privacy, and tenant boundaries;
- deterministic evaluators where possible and model-based evaluators only where necessary;
- keyed real-provider scorecards as explicit CI or release-gate artifacts; and
- negative tests proving no cross-tenant evidence, action, review, receipt, or conversation access.

This work should extend the current evaluation and metrics implementations rather than replace them.

### 7.2 Governed Inbound MCP Exposure

Add an optional MCP server integration that can expose explicitly approved:

- read-only specialists as tools;
- scoped retrieval as resources or tools;
- safe prompt templates where they provide product value; and
- governed write actions only through their existing confirmation/review/receipt lifecycle.

Security rules:

- Spring Security or a trusted server adapter constructs identity and tenant context;
- client arguments cannot declare tenant, deployment, subject, authority, provider secrets, or scopes;
- every exported capability is allowlisted by exact name and version;
- write tools never become direct side-effect methods;
- errors remain visible and no hidden local fallback changes behavior;
- cancellation and long-running execution map to existing durable execution semantics; and
- audit output records what was exposed, selected, invoked, denied, and projected.

Spring AI's MCP server starters should own protocol transport and negotiation. AI Fabric should own
the authorization, mapping, invocation, and projection policy.

### 7.3 Progressive Authorized Capability Discovery

Sending every action or MCP tool definition to a model becomes expensive and less accurate when a
deployment has dozens or hundreds of capabilities. The framework should add progressive disclosure,
but only after authorization.

The required order is:

```text
trusted identity
  -> effective capabilities
  -> authorized catalogue
  -> semantic selection inside that catalogue
  -> small exact-version capability set
  -> LLM selection
  -> existing governed invocation
```

The selector must never search a global unauthorized catalogue and filter after selection. It should
record safe evidence describing why a capability was surfaced. Spring AI's tool-search advisor can
provide infrastructure, while AI Fabric retains the catalogue and execution authority.

### 7.4 One Independent Design-Partner Proof

Select one bounded product template, preferably a read-only deployment/support specialist or a
governed account resolver. Measure:

- time from an existing Spring Boot application to first grounded result;
- modules and configuration actually required;
- failed setup attempts and documentation gaps;
- deterministic and real-provider scenario pass rates;
- retrieval quality and tenant-isolation results;
- provider usage, latency, and cost;
- support time required from the maintainer; and
- upgrade experience across one framework release.

The result should be a public, anonymized implementation report where the partner permits it.

## 8. P1: Build After P0 Foundations

### 8.1 Typed Execution Lifecycle Streaming

Keep current synchronous APIs and add an optional stream of stable events such as:

```text
EXECUTION_ACCEPTED
INTENT_RESOLVED
RETRIEVAL_COMPLETED
CAPABILITIES_SELECTED
ACTION_PROPOSED
CONFIRMATION_REQUIRED
REVIEW_REQUIRED
SPECIALIST_STARTED
SPECIALIST_COMPLETED
EXECUTION_COMPLETED
EXECUTION_FAILED
EXECUTION_CANCELLED
```

Events must carry safe references and typed metadata, not raw internal prompts, hidden reasoning, or
unsanitized partial output. An AG-UI adapter may translate these events for frontend consumers.

### 8.2 Policy-Governed Semantic Memory

First correct public language: AI Fabric currently provides persisted conversation turns and a
sliding context strategy, not a complete long-term semantic-memory product.

An optional semantic-memory capability should require:

- owner, tenant, deployment, and purpose scope;
- provenance back to approved conversation turns or domain events;
- retention and exact-delete policy;
- PII processing before storage;
- separation between authoritative domain facts and inferred memory;
- confidence, revision, and expiration metadata; and
- no raw-transcript vectorization by default.

Spring AI memory repositories and advisors may be used underneath this policy boundary.

### 8.3 Document And RAG Quality Hardening

The `0.8.x` document layer deliberately avoids becoming a crawler or document-management system.
Preserve that boundary.

Build next when supported by a real corpus:

- stable evidence and citation IDs in answer contracts;
- ingestion-quality reports and unsupported-content diagnostics;
- retrieval scorecards by entity/document type and tenant;
- active-version retrieval filtering if replacement overlap becomes measurable; and
- Markdown, HTML, PDF, or Tika readers using Spring AI's document layer.

Do not build OCR, crawling, connectors, or document editing until a named product needs them.

## 9. P2: Demand-Gated Capabilities

### 9.1 A2A Adapter Pilot

Build only when one real LoomAI or partner use case requires a remote agent. The adapter should map:

- exact allowlisted Agent Cards to specialist identities;
- AI Fabric trusted context to authenticated remote requests;
- durable execution to A2A tasks;
- typed outputs to A2A artifacts;
- status, cancellation, and streaming to existing execution state; and
- remote failures to explicit safe AI Fabric failures.

Remote discovery must never dynamically grant actions, vector spaces, identity, or tenant access.

### 9.2 PostgreSQL And Pgvector Provider Evaluation

Pgvector could reduce operational friction for Java teams already using PostgreSQL. Evaluate it
against the complete AI Fabric vector contract, including lifecycle, delete/update, metadata
filtering, counts, diagnostics, batching, tenant scope, and migration behavior. Do not ship a thin
similarity-search wrapper that cannot satisfy the broader contract.

### 9.3 Cost And Model Routing

Only add cost-aware routing after unified telemetry provides trustworthy measurements. Routing must
remain explicit policy with model allowlists, purpose, budget, latency bounds, and visible failure.
The model must not choose its own provider, and the framework must not silently downgrade or switch
providers.

### 9.4 Third-Party Marketplace Publishing

Continue first-party templates now. External publishing requires:

- artifact signatures and provenance;
- SBOM and dependency policy;
- declared capabilities and secrets;
- compatibility and behavioral verification;
- publisher identity and revocation;
- runtime isolation appropriate to executable content; and
- upgrade and retirement policy.

Until those controls exist, the marketplace should distribute trusted definitions and templates,
not arbitrary executable plugins.

## 10. Explicit Deferrals And Rejections

The following should not become near-term framework priorities:

- a generic graph or BPM/workflow engine;
- unrestricted recursive agents or model-generated execution topology;
- autonomous agent negotiation;
- Tree-of-Thought or self-consistency marketed as framework capabilities;
- automatic model fine-tuning from user conversations;
- framework-owned experimentation, A/B testing, or analytics platforms;
- gRPC solely because it may be faster without a measured transport bottleneck;
- a built-in crawler or document-management system;
- framework-owned operational dashboards or deployment control plane;
- replacing mature native vector providers with Spring AI `VectorStore`;
- hidden provider, deterministic, or local fallbacks that conceal AI failure;
- legal compliance certification claims; and
- more general-purpose demos before adoption and evaluation gaps are addressed.

These are not all invalid ideas. They are either owned by LoomAI, already served by a commodity
layer, inconsistent with AI Fabric's philosophy, or premature without external demand.

## 11. Proposed Delivery Sequence

### Phase 1: Truth And Baseline

- archive or clearly mark the old compliance scorecard as historical;
- publish the evidence-backed capability matrix;
- define the evaluation scenario format and baseline corpus;
- verify Spring AI `2.0.1` compatibility without changing behavioral contracts; and
- identify the design-partner product and success measures.

### Phase 2: Operational Trust

- establish end-to-end observation and correlation contracts;
- instrument orchestration, retrieval, actions, MCP, specialists, chains, and review;
- publish safe metrics and a reference dashboard;
- implement the expanded evaluation suite; and
- add cross-tenant and privacy negative gates.

### Phase 3: Interoperability

- implement governed inbound MCP exposure;
- prove it through Claude Code or another independent MCP client;
- add progressive authorized capability disclosure; and
- publish protocol compatibility and security guidance.

### Phase 4: Product Proof

- onboard the external design partner;
- run deterministic and real-provider release gates;
- record onboarding, quality, reliability, usage, and operational evidence; and
- let those results decide the P1 and P2 sequence.

## 12. Acceptance Gates

The next strategic release is ready only when:

- one trace can follow a request through every AI Fabric subsystem it actually uses;
- sensitive content is absent from telemetry by default;
- evaluation covers successful and adversarial behavior, not only happy-path answers;
- MCP clients can invoke only exact exported capabilities under trusted identity;
- a write requested through MCP still requires the same confirmation or review as any other channel;
- large catalogues expose only authorized, relevant tools to the model;
- errors and provider failures remain visible;
- existing synchronous APIs and real applications retain their behavior;
- the full unit, integration, packaged-runtime, and keyed real-provider gates pass; and
- one independent user can complete the documented product path with measured support effort.

## 13. Recommended Public Positioning

Use:

> **AI Fabric is the governed AI enablement runtime for existing Spring Boot applications. It lets
> models reason over approved application evidence and propose bounded operations while trusted
> application code retains identity, authorization, domain truth, transactions, and side effects.**

Use for the platform boundary:

> **LoomAI is the authoring, deployment, and operations control plane for AI Fabric-enabled
> applications.**

Avoid describing AI Fabric as the most complete agent framework or as a replacement for Spring AI,
LangChain4j, application services, vector databases, workflow engines, or deployment platforms.

## 14. External Primary References

- [Spring AI Tool Calling](https://docs.spring.io/spring-ai/reference/api/tools.html)
- [Spring AI MCP Server](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-server-boot-starter-docs.html)
- [Spring AI Observability](https://docs.spring.io/spring-ai/reference/observability/)
- [Spring AI Evaluation Testing](https://docs.spring.io/spring-ai/reference/api/testing.html)
- [Spring AI Chat Memory](https://docs.spring.io/spring-ai/reference/api/chat-memory.html)
- [Spring AI ETL Pipeline](https://docs.spring.io/spring-ai/reference/api/etl-pipeline.html)
- [LangChain4j Agents And Agentic AI](https://docs.langchain4j.dev/tutorials/agents/)
- [LangChain4j Tool Calling](https://docs.langchain4j.dev/tutorials/tools/)
- [A2A Protocol Specification](https://github.com/a2aproject/A2A/blob/main/docs/specification.md)
- [AG-UI Protocol](https://github.com/ag-ui-protocol/ag-ui/blob/main/docs/ag_ui.md)
- [OpenTelemetry Generative AI Semantic Conventions](https://opentelemetry.io/docs/specs/semconv/registry/attributes/gen-ai/)
- [MCP 2026-07-28 Specification Release](https://blog.modelcontextprotocol.io/posts/2026-07-28/)

## 15. Final Decision

AI Fabric should continue in this direction, but its next step is not broader autonomy. Its strongest
market position is governed AI enablement over existing Java application data and operations.

Build operational trust, complete MCP interoperability, scale authorized capability discovery, and
prove the product with an independent user. Defer generic graphs, unbounded autonomy, protocol work
without a real integration, and control-plane features that belong in LoomAI.
