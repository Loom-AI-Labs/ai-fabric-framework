# LoomAI AI Enablement Product And Deployment Template Proposal

- **Status:** Product and architecture proposal
- **Date:** 2026-07-31
- **Framework baseline:** AI Fabric `0.5.2`
- **Audience:** LoomAI product, platform, framework, application, and security teams
- **Scope:** Reusable AI-enabled product blueprints, deployment profiles, MCP connectivity, Claude integration, authoring flow, governance, and delivery order
- **Out of scope:** Implementing the proposed LoomAI control-plane or MCP-server contracts

## 1. Executive Summary

AI Fabric `0.5.2` gives LoomAI enough production-oriented runtime contracts to move beyond deploying
generic chat applications. LoomAI can offer **deployable, bounded AI capability templates** for Java
and Spring Boot applications.

The core product idea is:

```text
application data and registered operations
  + versioned AI Fabric specialist
  + trusted application identity and authority
  + provider and storage bindings
  + deployment policy and verification
  = deployable AI-enabled product capability
```

LoomAI should not sell an unrestricted autonomous-agent builder. It should let a developer select a
known product blueprint, bind it to approved application capabilities, validate it, and deploy an
immutable version with observable security and release evidence.

Product templates and deployment templates are related but different:

- a **product template** defines the user-visible AI capability;
- a **deployment profile** defines the runtime, storage, security, and operational topology needed
  by that capability;
- a **provider overlay** selects Claude, OpenAI, Gemini, or another supported model without changing
  product governance;
- a **channel overlay** exposes the same capability through an application API, AI Fabric Chat UI,
  an event adapter, Claude Code, or a future LoomAI MCP server.

The recommended first product is the existing deployment-knowledge specialist on AI Fabric `0.5.2`.
It is the smallest proof of the complete LoomAI direction: platform authoring, exact specialist
versioning, tenant- and deployment-scoped retrieval, provider-backed reasoning, and deployment
canaries.

## 2. Strategic Product Position

LoomAI can become the authoring, deployment, and operations layer for AI-enabled applications while
AI Fabric remains the application runtime and governance layer.

| Layer | Primary responsibility |
| --- | --- |
| Model provider, including Claude | Language understanding, reasoning, and schema-bound generation |
| MCP | Standard connectivity between AI clients, tools, data, and workflows |
| AI Fabric | Retrieval, specialist execution, trusted context, capability resolution, confirmations, durable receipts, review, and safe projection |
| LoomAI | Template catalogue, authoring, validation, deployment, secrets, environment binding, observability, rollout, and lifecycle |
| Host application | Authentication, domain truth, authorization, transactions, and reconciliation |

This division lets LoomAI support Claude and MCP without turning either one into the security model.

## 3. Framework Foundation Available Now

The proposal is based on shipped contracts, not only future architecture.

| Foundation | Available behavior | Evidence |
| --- | --- | --- |
| Specialist runtime | Exact `name@version`, typed Java or JSON Schema contracts, immutable YAML/JSON manifests, synchronous and asynchronous invocation | [AI Fabric 0.5.0 release notes](../release-notes/0.5.0.md) |
| Trusted authority | Application-built `TrustedExecutionContext`, capability intersection, backend-owned identity and scopes | [Agentic application guide](../Framework-Dev-Guides/application-patterns/AGENTIC_APP_GUIDE.md) |
| Trusted RAG boundary | Verified tenant, deployment, subject, caller, and scope metadata reaches retrieval; spoofed metadata is removed | [AI Fabric 0.5.2 release notes](../release-notes/0.5.2.md) |
| Interactive specialists | Backend conversation ownership, recent approved turns, typed missing-input waits, safe resume | [AI Fabric 0.5.0 release notes](../release-notes/0.5.0.md) |
| Governed writes | One registered write proposal, application confirmation, durable identity-bound receipt, idempotent replay, reconciliation | [Governed specialist writes guide](../Framework-Dev-Guides/actions-governance/GOVERNED_SPECIALIST_WRITES_AND_RECEIPTS.md) |
| Human review | Durable review task, reviewer authorization, correction, escalation, dispatch evidence, and governed approval | [Durable human review guide](../Framework-Dev-Guides/application-patterns/DURABLE_HUMAN_REVIEW.md) |
| Durable reads | JDBC-backed read jobs, leasing, recovery, scoped lookup, cancellation, and replay | [Durable read-only specialist jobs](../Framework-Dev-Guides/application-patterns/DURABLE_READ_ONLY_SPECIALIST_JOBS.md) |
| Bounded composition | Fixed sequential and parallel read-only plans, one-level delegation, one-level handoff, deterministic aggregation | [AI Fabric 0.5.0 release notes](../release-notes/0.5.0.md) |
| Live data enablement | Annotation-driven Data Sync, durable indexing work, vector retrieval, migration/backfill | [Live Data Sync app](../../examples/real-apps/ai-fabric-live-data-sync/README.md) |
| Indexing reconciliation | Public `IndexingWorkQuery` and safe `IndexingWorkStatus` lifecycle | [AI Fabric 0.5.1 release notes](../release-notes/0.5.1.md) |
| Anthropic provider | Claude generation through the Spring AI Anthropic provider | [Spring AI provider auto-configuration](../../ai-infrastructure-module/providers/ai-fabric-provider-spring-ai/src/main/java/ai/fabric/provider/springai/SpringAiProviderAutoConfiguration.java) |
| Outbound MCP tools | File-defined `mcp-tool` actions execute through Spring AI-managed MCP clients under AI Fabric action governance | [Spring AI MCP action executor](../../ai-infrastructure-module/ai-fabric-actions-connector/src/main/java/ai/fabric/intent/action/connector/springai/SpringAiMcpActionExecutor.java) |

These capabilities are optional. A template must include only the modules and storage required by
its actual workflow.

## 4. Template Composition Model

LoomAI should compose a deployment from four explicit artifacts instead of maintaining a separate
application implementation for every product.

```text
Product Blueprint
  + Deployment Profile
  + Provider Overlay
  + Channel Overlay
  = Versioned LoomAI Deployment Template
```

### 4.1 Product blueprint

Defines:

- product name and use case;
- one or more exact specialist definitions;
- input and output schemas;
- approved modes, vector spaces, actions, and evidence policies;
- interaction and review requirements;
- safe public result shape; and
- required verification scenarios.

### 4.2 Deployment profile

Defines:

- AI Fabric modules;
- application and framework database migrations;
- vector provider and vector-space bindings;
- conversation, receipt, execution, and review storage;
- worker and scheduling requirements;
- secrets and environment variables;
- health, readiness, and build metadata; and
- rollout, canary, and rollback behavior.

### 4.3 Provider overlay

Defines:

- active generation provider and explicit model ID;
- embedding provider and dimensions;
- provider endpoint, timeout, token budget, and retry policy;
- permitted fallback policy; and
- provider-specific verification.

The provider overlay must not change identity, authority, available actions, or vector-space
permissions.

### 4.4 Channel overlay

Defines how trusted application code invokes the same capability:

- normal application REST API;
- AI Fabric Chat UI;
- scheduled or application-event adapter;
- internal Java `SpecialistClient`;
- Claude Code through a LoomAI MCP server; or
- Claude Messages API through a LoomAI MCP server.

Channel input may supply user text and declared public fields. It may not supply identity, tenant,
deployment, authority, specialist ID, action authority, or provider credentials.

## 5. Product Template Catalogue

### 5.1 Deployment Knowledge Specialist

**Outcome:** An authenticated operator asks about deployments, configuration, incidents, indexing,
and runbooks and receives tenant- and deployment-scoped evidence.

**Composition:**

```text
Read-Only Knowledge
  + Tenant-Isolated SaaS
  + Live Data And RAG
  + Claude or another provider overlay
```

**Primary capabilities:** read-only specialist, trusted retrieval metadata, approved vector spaces,
safe evidence references, indexing status, explicit provider failure.

**Priority:** P0. This is the first AI Fabric `0.5.2` LoomAI proof.

### 5.2 Customer Account Resolver

**Outcome:** The specialist reads current account state, explains blockers, asks for supported
missing information, proposes one resolution, waits for confirmation, executes, and reconciles.

**Composition:**

```text
Interactive Resolver
  + Tenant-Isolated SaaS
  + Policy RAG
  + Governed Write
```

**Primary capabilities:** backend conversation memory, typed input waits, registered actions,
durable receipts, idempotency, safe result projection.

**Reference:** [Agentic AI Action Resolver](../../examples/real-apps/agentic-ai-action-resolver/README.md).

### 5.3 Support Resolution Specialist

**Outcome:** Investigate a support case, retrieve customer and policy evidence, recommend a remedy,
propose a credit or refund, and route high-impact cases to review.

**Composition:** Interactive Resolver + Governed Write + Human Review.

**Primary capabilities:** evidence-grounded decision, typed clarification, review task, correction,
approval, rejection, escalation, durable action outcome.

### 5.4 Incident Investigation Specialist

**Outcome:** Combine deployment status, logs, recent changes, service health, and runbooks into a
structured diagnosis and recommended response.

**Composition:** Bounded Multi-Specialist + Read-Only Knowledge + optional outbound MCP tools.

**Primary capabilities:** fixed sequential or parallel read-only plan, exact specialist versions,
typed step mappings, deterministic Java aggregation, evidence lineage.

The model may reason over branch results. It may not create a runtime graph or select arbitrary
workers.

### 5.5 Behavior And Risk Analyst

**Outcome:** Consume trusted application events and produce churn, fraud, adoption, or operational
risk insight. Later events update the previous insight rather than masquerading as a new complete
history.

**Composition:** Durable Event Worker + structured output + optional Personalized Experience
Planner.

**Primary capabilities:** typed event adapter, durable read job, previous-insight plus new-events
contract, replay binding, safe terminal snapshot.

This template must not execute an automatic write merely because a model recommends one.

### 5.6 Live Data Knowledge Assistant

**Outcome:** Keep selected domain entities synchronized with vector evidence and let users observe
source changes, indexing work, and grounded answer changes.

**Composition:** Live Data And RAG + Read-Only Knowledge.

**Primary capabilities:** annotation lifecycle, create/update/delete synchronization,
`IndexingWorkQuery`, migration/backfill, multi-entity and multi-space retrieval.

### 5.7 Policy And Compliance Assistant

**Outcome:** Answer only from approved policy evidence while protecting tenant boundaries and
sensitive information.

**Composition:** Read-Only Knowledge + Tenant-Isolated SaaS + Privacy overlay.

**Primary capabilities:** evidence-only answers, PII handling, metadata allowlisting, safe source URL
policy, fail-closed access denial.

### 5.8 Human-Reviewed Operations Assistant

**Outcome:** Produce a durable decision package for a sensitive operation and let an authenticated
reviewer approve, reject, correct, request information, or escalate it.

**Composition:** Human Review + Governed Write.

**Primary capabilities:** version-bound review task, separate delivery record, reviewer authority,
linked write receipt, safe final projection.

### 5.9 MCP Operations Assistant

**Outcome:** Use standardized external tools and APIs while preserving AI Fabric parameter,
authorization, confirmation, receipt, and output policies.

**Composition:** Outbound MCP + Read-Only Knowledge or Governed Write.

**Primary capabilities:** MCP tool discovery, exact server/tool binding, argument projection,
structured result mapping, access modes, confirmation, failure visibility.

**Current proof limitation:** The existing [MCP Operations Assistant](../../examples/real-apps/mcp-operations-assistant/README.md)
uses a deterministic local executor. It proves governance shape, not live remote MCP operation.

### 5.10 Personalized Experience Planner

**Outcome:** Return a short allowlisted list of application components based on current user insight,
with a reason for each choice, so the application can compose a relevant interface.

**Composition:** Behavior And Risk Analyst + structured catalogue selection.

**Primary capabilities:** structured output, application-owned component catalogue, safe names and
reasons, deterministic UI rendering. The model never returns executable UI code.

## 6. Reusable Deployment Profiles

| Profile | Runtime shape | Required state | Important caveat |
| --- | --- | --- | --- |
| Read-Only Knowledge | Specialist + RAG + generation + vector provider | Vector evidence; domain truth remains in app | Evidence must be scoped and sanitized |
| Interactive Resolver | Read specialist + conversation + typed waits | Configured chat-session store; input waits are currently process-local | Use affinity or avoid restart-sensitive waits |
| Governed Write | Specialist proposal + confirmation + action execution | JDBC `ai_action_proposal_receipt` plus stable secrets | Side effect remains application-owned |
| Human Review | Review policy + reviewer API + governed receipt | JDBC `ai_review_task` and `ai_review_dispatch` | Approval does not bypass action authorization |
| Durable Event Worker | Trusted event adapter + worker + recovery | JDBC `ai_specialist_execution` plus stable secrets | At-least-once read execution, not exactly-once provider calls |
| Live Data And RAG | Data Sync + indexing worker + vector provider | Indexing queue/state and vector storage | Use work status, not vector existence, as completion proof |
| Bounded Multi-Specialist | Fixed sequential/parallel plan | Plan state is currently process-local | Read-only, exact-version, deterministic topology |
| Tenant-Isolated SaaS | Trusted auth adapter + scoped retrieval + canaries | Application identity source and tenant-aware domain/vector data | Requires AI Fabric `0.5.2` for specialist RAG |

Deployment profiles must be composable. A product should not duplicate migrations, health checks,
or security code from another product.

## 7. MCP In The LoomAI Vision

MCP is both an outbound integration boundary and a future inbound product channel.

```text
Claude / Claude Code / other MCP client
              |
      future LoomAI MCP server
              |
       LoomAI authentication
              |
     TrustedExecutionContext
              |
      AI Fabric specialist
              |
   AI Fabric governed MCP action
              |
      external MCP server
```

### 7.1 Outbound MCP: partially available now

AI Fabric currently supports file-catalog actions with:

- `adapterType: mcp-tool`;
- `serverRef` and `toolName`;
- templated public and trusted runtime arguments;
- response-path mapping;
- Spring AI-managed `McpSyncClient` instances;
- AI Fabric access modes and confirmation policy; and
- sanitized `ActionResult` projection.

This makes MCP a transport behind an AI Fabric action. MCP does not bypass action governance.

### 7.2 Required outbound hardening

Before calling this a production LoomAI MCP gateway:

1. fail closed when a declared `serverRef` does not resolve;
2. prove a real Streamable HTTP MCP server with authentication;
3. bind every server and tool to an application/deployment allowlist;
4. verify tenant and deployment context cannot be supplied by action parameters;
5. bound and sanitize tool schemas, arguments, content, structured content, and metadata;
6. preserve MCP failure instead of silently falling back to another tool or server;
7. add live read and governed-write canaries; and
8. define secret rotation, timeouts, retries, circuit breaking, and audit behavior.

The current executor searches clients by declared server information and tool catalogue. The
production contract should reject an unresolved declared server instead of considering a different
client that happens to expose the same tool name.

### 7.3 Inbound MCP: proposed LoomAI capability

LoomAI should expose an authenticated remote Streamable HTTP MCP server. It should project stable
product operations, not every internal framework class.

Candidate tools:

```text
loomai_get_authoring_catalog
loomai_validate_specialist
loomai_create_deployment_draft
loomai_run_release_checks
loomai_deploy
loomai_get_deployment_status
loomai_execute_specialist
loomai_get_specialist_execution
loomai_resume_specialist
loomai_get_indexing_work_status
loomai_confirm_action_receipt
loomai_reject_action_receipt
loomai_get_review_task
loomai_decide_review_task
```

Do not expose a generic `execute_any_action`, `invoke_any_specialist`, arbitrary URL fetch, raw SQL,
or unrestricted shell tool.

Candidate resources and prompts may later include:

- AI Fabric module and capability documentation;
- the deployment's safe authoring catalogue;
- immutable specialist schema and manifest examples;
- current deployment health and safe diagnostics;
- a prompt for adding one bounded specialist to a Spring Boot application; and
- a prompt for running a deployment readiness review.

The first release should focus on tools. Resources and prompts can follow after authorization and
stable URI design are proven.

## 8. Claude Integration

Claude can participate in three different roles. They should remain separate configuration choices.

### 8.1 Claude as the specialist model

AI Fabric already registers an Anthropic `AIProvider` through Spring AI when explicitly enabled.
A template should require an explicit model ID rather than relying on a compiled default:

```yaml
ai:
  providers:
    llm-provider: anthropic
    anthropic:
      enabled: true
      api-key: ${ANTHROPIC_API_KEY}
      model: ${ANTHROPIC_MODEL}
```

Claude may provide reasoning and schema-bound output for retrieval, specialist execution,
clarification, action proposals, and response generation. AI Fabric still owns authority,
retrieval scope, confirmation, persistence, and projection.

Anthropic does not provide the embedding implementation used by the current AI Fabric Spring AI
path. A Claude deployment must bind a separate supported embedding provider such as ONNX, OpenAI,
or Gemini when RAG is enabled.

### 8.2 Claude Code as a developer channel

A developer can connect Claude Code to the proposed LoomAI MCP server and work conversationally:

```text
inspect application
  -> read LoomAI authoring catalogue
  -> propose specialist manifest and schemas
  -> edit application configuration or code locally
  -> validate against deployed capability inventory
  -> create deployment draft
  -> run compilation, tests, and canaries
  -> ask the developer to approve deployment
  -> deploy immutable specialist version
  -> inspect health and safe diagnostics
```

This is a product opportunity, not a current end-to-end capability. AI Fabric manifest validation,
Claude Code MCP connectivity, and LoomAI deployment behavior exist as separate concerns; LoomAI
must build the authenticated MCP authoring/deployment boundary that joins them.

### 8.3 Claude Messages API as an application channel

The Claude Messages API can connect to a remote MCP tool server. LoomAI may support this as an
optional channel after its MCP server is stable.

For governed application operations, use:

```text
Claude
  -> LoomAI MCP server
  -> AI Fabric specialist and action governance
  -> approved downstream MCP server or application action
```

Do not connect Claude directly to sensitive operational MCP servers when AI Fabric confirmation,
tenant policy, receipt, review, or reconciliation is required. A direct connection would move the
tool call outside the AI Fabric policy boundary.

Official implementation references:

- [Claude Code MCP](https://code.claude.com/docs/en/mcp)
- [Claude MCP connector](https://platform.claude.com/docs/en/agents-and-tools/mcp-connector)
- [MCP authorization](https://modelcontextprotocol.io/specification/2025-06-18/basic/authorization)
- [Spring AI MCP](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-overview.html)

## 9. Conversational Authoring And Deployment

The target experience is valid:

> Install the LoomAI MCP connection in Claude Code, then inspect, AI-enable, test, deploy, and
> diagnose a Spring Boot application conversationally.

It must be implemented as a controlled workflow.

### Example request

```text
Add a deployment-knowledge specialist to this Spring Boot application.
It should answer from deployment and runbook data, be limited to the
authenticated tenant, and never perform writes.
```

Claude Code may propose:

```yaml
name: deployment-knowledge
version: "1"
mode: deep
capabilities:
  vectorSpaces:
    - deployment-knowledge
  actions:
    - get_deployment_status
grounding:
  required: true
outputSchema: deployment-knowledge-response-v1
```

That file is a draft until LoomAI proves:

- the specialist identity and schema are valid;
- the mode exists;
- the vector space and action are registered for that deployment;
- the requested capability set is no broader than deployment inventory and caller scope;
- trusted tenant and deployment context comes from backend authentication;
- required modules, migrations, providers, and secrets exist;
- unit, integration, packaged-runtime, and security canaries pass; and
- the user confirms deployment.

### Authoring boundary

Claude may:

- inspect public source and configuration supplied by the developer;
- read the authorized LoomAI catalogue;
- create or edit source-controlled manifests and schemas;
- propose module and provider configuration;
- request validation and test execution; and
- create a deployment draft.

Claude may not:

- invent or grant an action, vector space, provider, scope, or deployment capability;
- place secrets in source or manifests;
- accept tenant, principal, or authority from a prompt;
- bypass a failed validator or canary;
- deploy without an authenticated confirmation; or
- rewrite a deployed specialist under an existing version.

## 10. Proposed Template Package

Each LoomAI template should be an immutable, reviewable package:

```text
template/
  template.yml
  specialists/
    deployment-knowledge-v1.yml
  schemas/
    deployment-query-v1.schema.json
    deployment-answer-v1.schema.json
  actions/
    approved-actions.yml
  retrieval/
    vector-spaces.yml
    metadata-policy.yml
  deployment/
    modules.yml
    storage.yml
    providers.yml
    health.yml
  migrations/
  verification/
    deterministic-cases.yml
    provider-cases.yml
    security-canaries.yml
  docs/
    README.md
```

Illustrative descriptor:

```yaml
apiVersion: loomai.io/v1alpha1
kind: AIEnablementTemplate
metadata:
  name: deployment-knowledge-specialist
  version: "1.0.0"
product:
  specialists:
    - deployment-knowledge@1
  deploymentProfile: tenant-safe-read-only-rag
  providerOverlay: configurable
  channels:
    - application-api
    - claude-code-mcp
requirements:
  aiFabricVersion: "0.5.2"
  modules:
    - ai-fabric-execution
    - ai-fabric-rag
    - ai-fabric-provider-spring-ai
  vectorSpaces:
    - deployment-knowledge
security:
  trustedContextRequired: true
  tenantRequired: true
  deploymentRequired: true
  groundingRequired: true
verification:
  requiredSuites:
    - compile
    - deterministic
    - packaged-runtime
    - provider
    - cross-tenant
    - cross-deployment
```

This is a proposed LoomAI package, not a new AI Fabric public contract. LoomAI should first prove it
with one template before standardizing the schema.

## 11. Security And Governance Requirements

Every template and channel must enforce the following:

1. Identity, subject, tenant, deployment, and scopes come from authenticated backend context.
2. Specialist manifests request capabilities; they never grant authority.
3. Actions and vector spaces resolve from an application-owned registered catalogue.
4. Retrieval applies exact approved vector spaces and tenant/deployment filters.
5. Untrusted caller metadata cannot override trusted retrieval metadata.
6. Model output is schema-bound, validated, and safely projected.
7. Writes remain proposals until application confirmation or authorized review.
8. Confirmation accepts only an identity-bound receipt and decision, not replacement parameters.
9. Reconciliation checks the host application's system of record after a write.
10. Provider, MCP, validation, policy, persistence, and indexing failures remain visible.
11. Secrets remain in LoomAI's secret boundary and never enter prompts or manifests.
12. Logs exclude prompts, completions, raw PII, credentials, protected receipts, and hidden context.
13. A semantic specialist change creates a new exact version and content hash.
14. Rollback stops new invocations without deleting unresolved receipts, reviews, or durable work.

For remote MCP, the server must be an OAuth-protected resource and map token identity to trusted
LoomAI and AI Fabric context. Tool arguments must never be treated as authenticated identity.

## 12. Deliberate Non-Goals

Do not market or implement the following as current templates:

- unrestricted autonomous agents;
- model-generated or recursive workflow graphs;
- arbitrary model-selected tools, URLs, providers, credentials, or MCP servers;
- write-capable parallel or multi-specialist plans;
- tenant-authored executable scripts, SQL, or expressions;
- exactly-once external provider or MCP calls;
- model-owned authorization, confirmation, review, or reconciliation;
- silent deterministic fallback that hides an LLM, MCP, retrieval, or provider failure;
- runtime mutation of an already deployed specialist version; or
- direct Claude access to sensitive downstream MCP tools when governance is required.

## 13. Recommended Delivery Order

### P0: Prove the `0.5.2` deployment-knowledge product

1. Deploy LoomAI against published AI Fabric `0.5.2` artifacts.
2. Enable one exact deployment-knowledge specialist.
3. Bind tenant and deployment only from trusted runtime context.
4. Run cross-tenant, cross-deployment, missing-scope, provider-failure, and evidence-boundary canaries.
5. Record specialist ID, content hash, provider, vector spaces, deployment, and verification evidence.

### P1: Formalize reusable LoomAI template composition

1. Define the internal product blueprint and deployment-profile model.
2. Extract the Deployment Knowledge and Account Resolver templates.
3. Generate module, storage, provider, health, and canary requirements.
4. Keep generated files source-controllable and reviewable.
5. Add template compatibility and upgrade tests.

### P2: Productionize outbound MCP

1. Make declared server selection fail closed.
2. Add a live authenticated Streamable HTTP MCP reference server.
3. Prove read-only tools and confirmed write tools through AI Fabric.
4. Add allowlists, output bounds, timeout/retry policy, and audit evidence.
5. Add a production MCP Operations template.

### P3: Build the LoomAI authoring and deployment MCP server

1. Expose read-only authoring catalogue and validation tools first.
2. Add authenticated draft creation and release-check execution.
3. Add explicitly confirmed deployment.
4. Publish a Claude Code project configuration and developer guide.
5. Add OAuth, token-to-trusted-context mapping, rate limits, and full audit.

### P4: Expand product templates

1. Support Resolution with human review.
2. Incident Investigation with a fixed read-only plan.
3. Behavior and Risk Analyst with durable event execution.
4. Live Data Knowledge Assistant with indexing-status operations.
5. Personalized Experience Planner with an allowlisted component catalogue.

Do not build every product before the template abstraction is proven. Do not build the entire
abstract platform before one complete product proves it. Implement a common template spine and
expand it through production-quality vertical proofs.

## 14. Verification Strategy

Every generated deployment must have machine-readable release evidence.

| Gate | Required proof |
| --- | --- |
| Static validation | Manifest, schema, module, action, vector-space, and deployment inventory compatibility |
| Unit | Input/output adapters, validators, projectors, parameter policy, and failure projection |
| Integration | Retrieval, chat, action, receipt, review, indexing, and storage behavior used by the template |
| Packaged runtime | Built artifact starts with production-like configuration and exact AI Fabric version |
| Provider | Real configured LLM and embedding call; failure remains visible |
| Security | Cross-principal, subject, tenant, deployment, scope, evidence, and receipt denial |
| MCP | Exact server/tool binding, authentication, argument projection, output sanitation, unavailable-server behavior |
| Restart | Required receipt, review, execution, and indexing state survives restart |
| Deployment | Health reports source commit, template version, specialist ID/hash, AI Fabric version, and provider readiness |
| Rollback | New invocation stops while unresolved durable obligations remain recoverable |

The LoomAI MCP server must have its own tests proving a caller cannot place identity, tenant,
deployment, scopes, specialist authority, action authority, or secrets in tool arguments.

## 15. Product Success Measures

The proposal succeeds when LoomAI can demonstrate that:

1. A developer selects a product template instead of hand-assembling framework modules.
2. LoomAI generates a complete, reviewable deployment draft.
3. The same product can use Claude or another provider without changing governance.
4. The same specialist can be invoked through an application API and Claude Code without changing
   trusted identity behavior.
5. A data or action capability unavailable to the application cannot be invented by the model or
   template.
6. Cross-tenant and cross-deployment canaries fail closed.
7. A confirmed write executes once and replays the same durable result after restart.
8. A live MCP tool is governed exactly like a native application action.
9. Provider or MCP failure remains visible and actionable.
10. The deployed specialist version, content hash, template version, framework version, and release
    proof are observable.

## 16. Final Recommendation

Proceed with LoomAI product and deployment templates.

The correct positioning is:

> LoomAI turns registered Java application data and operations into deployable, governed AI-enabled
> capabilities. AI Fabric supplies the runtime contracts; LoomAI supplies authoring, deployment,
> provider and storage binding, verification, and lifecycle management.

Treat MCP as the standard connectivity layer, Claude as an optional reasoning provider and client,
AI Fabric as the policy and execution boundary, and LoomAI as the product control plane.

Start with the deployment-knowledge specialist on `0.5.2`, formalize the reusable template spine,
then productionize outbound MCP and expose LoomAI authoring safely to Claude Code.
