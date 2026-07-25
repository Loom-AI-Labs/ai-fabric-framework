# NotebookLM Single-Source Production Script: AI Fabric Architecture And Module Map

## Generator Instructions - Do Not Narrate

Use this file as the only source for the video. Do not add general AI architecture, external
framework knowledge, or assumptions about AI Fabric. Do not ask for or rely on another source.

Create a structured technical explainer titled **AI Fabric Architecture And Module Map**. Follow the
thirteen scenes in order. Treat each **Visual** block as production direction and each **Narration**
block as the spoken message. You may make transitions sound natural, but do not omit, replace, or
contradict the technical content.

Keep AI Fabric and its current Java module boundaries at the center of the video. This is not a
generic microservices, Spring AI, RAG, agent, or vector-database lesson. Do not invent module names,
APIs, dependencies, diagrams, benchmarks, users, or compliance claims. Apply the accuracy
guardrails at the end of this file to the complete output.

## Production Direction

- Title: **AI Fabric Architecture And Module Map**
- Subtitle: **How a Spring Boot request moves through application policy, AI Fabric capabilities,
  and concrete providers**
- Target duration: 11-14 minutes.
- Audience: Java and Spring Boot developers who understand what AI Fabric is and now need to choose
  modules and trace the framework behind a real request.
- Voice: direct, calm, technically precise, and practical. Speak to the developer as **you**.
- Learning objective: by the end, you can place a requirement at the correct architecture boundary,
  select the smallest valid module set, and trace retrieval and action requests without assigning
  business ownership to the LLM or browser.
- Visual style: use clean layered diagrams, dependency blocks, request-flow animation, and brief
  reviewed Java shapes. Keep diagrams readable. Do not use decorative robots or invented dashboards.

## Scene 1: Begin With A Requirement, Not A Module

**Visual:** Show an existing Spring Boot application with controllers, services, repositories,
authorization, and a database. Add three requirements:

```text
Find relevant support knowledge.
Answer using retrieved evidence.
Update an account only after confirmation.
```

**Narration:**

AI Fabric is modular, so the architecture starts with an application requirement rather than a list
of dependencies.

Suppose your Spring Boot application must find relevant support knowledge, answer from that evidence,
and update an account only after the user confirms. Those are three related capabilities, but they
do not require one giant AI runtime.

Semantic search needs an embedding provider, a vector provider, and AI Fabric's indexing and search
contracts. Evidence-grounded generation adds RAG and an LLM provider. A governed write adds a
registered application action, policy, and confirmation state.

The application still owns its support records, account records, authorization, and side effects.
AI Fabric connects those assets to model and retrieval capabilities. The architecture is easier to
understand when every module has one reason to exist.

## Scene 2: See The Five Architecture Layers

**Visual:** Build this diagram from top to bottom. Keep ownership labels visible.

```text
1. UI or external API
2. Spring Boot application: identity, policy, services, repositories
3. AI Fabric: orchestration and capability modules
4. AI Fabric provider contracts and adapters
5. LLMs, embedding models, vector stores, and application databases
```

**Narration:**

The public AI Fabric architecture has five practical layers.

At the top, a UI or external API sends a request. It presents results, but it must not manufacture
AI intent, tenant scope, or action approval.

The Spring Boot application owns authenticated identity, business policy, domain services,
repositories, and the response contract exposed to users.

AI Fabric sits inside that application as capability modules and orchestration. It coordinates
retrieval, generation, actions, conversation state, privacy, and governance according to configured
policy.

Provider contracts isolate those capabilities from a concrete LLM, embedding model, or vector
database.

At the bottom, concrete systems perform inference and persistence. The layers are not a sequence in
which every request must visit every box. They are ownership boundaries. A retrieval-only request
does not require an LLM, and a simple generation request does not require a vector store.

## Scene 3: Understand BOM, Core, And Starter

**Visual:** Show three blocks with precise labels.

```text
ai-fabric-bom       -> version alignment; no application capability by itself
ai-fabric-core      -> contracts, entity processing, search, orchestration, actions
ai-fabric-starter   -> convenience bundle: core + PII + indexing
```

**Narration:**

Three foundation artifacts are easy to confuse.

`ai-fabric-bom` aligns AI Fabric module versions under the Maven group
`io.github.loom-ai-labs`. A BOM manages dependency versions; it does not activate runtime behavior.

`ai-fabric-core` supplies the main framework contracts and services. These include generation and
embedding abstractions, search coordination, AI entity configuration, orchestration primitives,
local action discovery, prompt resolution, and Spring components used by the pipeline. Core also
uses the default curated prompt family.

`ai-fabric-starter` is a convenience dependency. In the current release it pulls in core, PII, and
indexing. It does not pull in an LLM provider, an embedding provider, a vector provider, RAG,
chat-session, governance, or behavior analysis.

Spring Boot wires the installed modules through standard auto-configuration imports. The
`EnableAIInfrastructure` annotation is an optional marker for discoverability. Adding it does not
replace dependency selection, and the framework does not depend on it to import configuration.

This distinction prevents a common mistake: adding the starter and assuming every AI capability and
provider now exists.

## Scene 4: Model The AI-Facing Projection

**Visual:** Show a small domain entity. Highlight the difference between searchable content and
structured context.

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

    @AISearchable
    private String title;

    @AISearchable
    private String body;

    @AIContext
    private String tenantId;
}
```

**Narration:**

AI Fabric does not need every field in your domain model.

`AICapable` identifies the application entity and its stable AI Fabric entity type. `AIIdentity`
identifies the source record. `AISearchable` marks approved text whose meaning should contribute to
an embedding and retrieval. `AIContext` marks structured context with typed, destination-specific
handling so metadata, model context, and response views do not have to expose the same values.

The application owns this projection. Passwords, internal notes, privileged flags, and unrelated
fields do not become model-visible merely because the entity exists.

An annotation-backed entity needs no `ai-entity-config.yml` entry. Typed YAML can apply documented
operational or field overrides, but it cannot replace the entity identity or widen approved security
destinations. YAML-only push entities must explicitly enable indexing and declare their projection.

These declarations describe what should happen. They do not prove that records were indexed,
updated, deleted, or protected correctly. Lifecycle tests provide that proof.

## Scene 5: Follow Spring Boot Auto-Configuration

**Visual:** Animate dependencies entering the classpath, then separate auto-configuration blocks
activating only when their conditions and required beans are present.

```text
Classpath dependencies + application properties + application beans
  -> module auto-configuration
  -> AI Fabric services and adapters
```

**Narration:**

Each installed AI Fabric module contributes its own Spring Boot auto-configuration.

Core creates framework services only when their requirements are satisfied. For example, semantic
search needs a configured vector service and enabled search support. Embedding support needs an
`EmbeddingProvider`. Vector management needs a concrete `VectorDatabaseService`. Optional modules
such as indexing, RAG, PII, chat-session, governance, and the Spring AI provider add their own beans
from their own artifacts.

Application beans remain part of the architecture. You provide domain repositories, access policy,
action handlers, identity context, and any deliberate overrides. Conditional configuration lets an
application replace selected defaults instead of forking the framework.

When a capability is absent, inspect three things in order: is the module on the classpath, is its
configuration enabled, and are its required provider or application beans available? Do not diagnose
missing wiring by adding unrelated modules.

## Scene 6: Trace The Orchestration Pipeline

**Visual:** Show a natural-language request moving through a policy-shaped pipeline.

```text
request context
  -> security and access checks
  -> named orchestration policy
  -> intent and target understanding
  -> retrieval or registered action handling
  -> result normalization and sanitization
  -> optional history persistence
```

**Narration:**

Orchestration is the coordination layer between a natural-language request and allowed application
capabilities.

AI Fabric builds its pipeline from Spring components. Depending on the configured workflow, the
pipeline can resolve policy, analyze security and access, extract intent, resolve targets and vector
spaces, retrieve evidence, propose or execute registered actions, produce suggestions, normalize the
result, sanitize the response, and persist history.

Named modes under `ai.orchestration.modes` define typed server-side behavior such as whether actions,
retrieval, deep retrieval, RAG, or suggestions are enabled. A request position is different. Web or
application position is context for routing and presentation policy; it is not a substitute for a
configured mode.

The browser may request an allowed mode, but the server owns the allowlist and default. The LLM can
interpret a request within that policy, but it cannot enable a capability that the application did
not configure.

## Scene 7: Separate The Three Provider Boundaries

**Visual:** Show three independent interfaces and their concrete implementations.

```text
AIProvider              -> LLM generation
EmbeddingProvider       -> text-to-vector inference
VectorDatabaseService   -> vector search and lifecycle operations
```

**Narration:**

AI Fabric has three provider boundaries that solve different problems.

The LLM provider generates content and supports intent-oriented orchestration. The embedding provider
turns approved text and queries into compatible numeric vectors. The vector provider stores vectors,
searches them, and exposes the lifecycle and administrative operations required by AI Fabric.

`ai-fabric-provider-spring-ai` bridges AI Fabric LLM generation to configured OpenAI, Azure OpenAI,
Anthropic, and Gemini paths. It also supplies remote embeddings for the supported OpenAI, Azure
OpenAI, and Gemini paths. It does not provide vector storage.

`ai-fabric-onnx-starter` supplies local embeddings without a cloud key. Vector modules remain
separate: Lucene for local durable storage, memory for controlled smoke tests, and Qdrant, Pinecone,
Weaviate, or Milvus for managed or external deployments.

This separation lets one application use a Spring AI-backed LLM, local ONNX embeddings, and Lucene
vectors, or replace one layer without rewriting its domain services.

## Scene 8: Build Semantic Search From Four Pieces

**Visual:** Highlight only the modules needed for a local search path.

```text
ai-fabric-starter
+ ai-fabric-onnx-starter
+ ai-fabric-vector-lucene
= local semantic search and indexing
```

**Narration:**

For a local semantic-search workflow, the starter contributes core and indexing. Add one embedding
provider, such as ONNX, and one vector provider, such as Lucene.

The indexing coordinator decides whether configured work executes synchronously or enters the
indexing queue. AI Fabric projects approved content and metadata, asks the embedding provider for a
vector, and stores the entity type, stable ID, content, vector, and metadata through the vector
service.

At query time, `AICoreService` embeds the search query and delegates an `AISearchRequest` to search
services backed by the same compatible vector space. The result is evidence: identity, content,
metadata, and ranking score.

No LLM is required for this path. If source records exist but indexing did not succeed, a correct
search can return no evidence. That empty result is useful operational truth.

## Scene 9: Add RAG Without Confusing It With Retrieval

**Visual:** Extend the previous flow with one optional generation stage.

```text
question -> embedding -> vector evidence
                              |
                              v
                    RAG context assembly -> LLM -> grounded response
```

**Narration:**

RAG begins after retrieval is working.

Add `ai-fabric-rag` and an LLM provider to the semantic-search module set. AI Fabric retrieves
evidence, builds approved context, and asks the configured model to produce an answer from that
context.

The vector provider does not write the answer. The LLM does not decide whether a source record was
indexed. The RAG module coordinates the handoff between evidence and generation.

Tests should inspect both layers. First prove which evidence was retrieved. Then prove the generated
response carries that evidence or reports the no-evidence state honestly. Fluent model text is not
proof that retrieval worked.

## Scene 10: Add Governed Actions And Memory

**Visual:** Show an annotated action bean beside the action pipeline.

```java
@AIAction(
    name = "update_payment_method",
    description = "Update the current account payment method",
    accessMode = ActionAccessMode.READ_WRITE,
    requiresConfirmation = true
)
class UpdatePaymentMethodAction {
    // Application-owned policy and execution methods
}
```

```text
intent -> allowed action -> typed parameters -> confirmation
       -> application handler -> structured result
```

**Narration:**

Local application actions are discovered through `AIAction` components and the core action registry.
An action declares a stable name, a user-facing description, an access mode, and whether confirmation
is required. Application methods provide policy, confirmation facts, and execution behavior.

The LLM can help map natural language to an allowed action and its typed parameters. The registry
limits discovery to known actions. Access checks and application policy decide whether the current
request may proceed. A write that requires confirmation returns pending state before the application
handler performs the side effect.

Add `ai-fabric-chat-session` when history, pending actions, and short follow-ups such as "yes" or
"use that one" must survive across requests in backend-owned state. Connector and database action
registry modules are optional extensions for external or runtime-managed catalogs; they are not
required for a local annotated action.

## Scene 11: Place Privacy, Governance, And Tenant Safety Correctly

**Visual:** Place three gates around the request and evidence flow: PII, Access Policy, and Metadata
Scope.

**Narration:**

Security is not one prompt and not one module.

The PII capability detects and applies configured handling to sensitive text. Because the starter
includes PII, applications using the starter must still choose and test the policy they intend; a
dependency alone does not decide whether to redact, encrypt an original, reject, or allow data.

Governance adds policy and audit-oriented behavior where the application needs it. Authorization and
tenant identity remain application-owned. The application exposes trusted identity and access policy
to AI Fabric rather than accepting a privileged tenant or account ID from the browser.

Tenant-safe retrieval also depends on indexed metadata and a vector provider that can enforce the
required filter. If policy or provider capability is uncertain, the safe behavior is to fail closed.

Privacy, governance, and tenant boundaries must be proved at the backend and provider request. A UI
badge or hidden field is not enforcement.

## Scene 12: Place The Optional Capability Modules

**Visual:** Arrange optional modules around the core request paths. Give each one a one-line purpose.

```text
ai-fabric-web                  optional framework HTTP surface
ai-fabric-behavior             LLM-backed analysis of application events
ai-fabric-relationship-query   relationship-aware retrieval
ai-fabric-data-sync            source-to-AI data synchronization
ai-fabric-migration-core       controlled migration and backfill
actions connector/registry     external or runtime action catalogs
curated commerce/support       domain prompt overlays
```

**Narration:**

The remaining modules extend a proven workflow.

`ai-fabric-web` supplies optional framework HTTP surfaces when an application wants them. Many real
applications expose their own controller and response shape instead.

`ai-fabric-behavior` analyzes raw application events with an LLM-backed workflow. The application
still owns event collection and the decision about what to do with an insight.

Relationship query, data synchronization, and migration modules support richer retrieval,
source-system synchronization, backfill, and lifecycle operations. Connector and registry modules
extend action discovery. Curated commerce and support artifacts overlay domain-specific prompt
families on the default curated behavior.

Optional means requirement-driven. Do not add behavior analysis to obtain chat memory, RAG to obtain
semantic search, or a connector registry to execute one local action.

## Scene 13: Use A Repeatable Module-Selection Checklist

**Visual:** Show the checklist, then apply it to three examples.

```text
1. What application outcome is required?
2. Which data and side effects remain application-owned?
3. Is retrieval, generation, action execution, memory, or analysis needed?
4. Which one embedding, vector, and LLM provider is required?
5. Which privacy, access, and tenant policy must fail closed?
6. What test proves each boundary worked?
```

**Narration:**

Use the same checklist for every AI Fabric feature.

For semantic search, choose the starter, one embedding provider, and one vector provider. Prove
indexing and retrieval without an LLM.

For evidence-grounded answers, add RAG and an LLM provider. Prove evidence separately from generated
wording.

For a confirmed account update, register an application action and add backend session persistence
when the conversation spans requests. Prove policy, pending confirmation, execution, and the domain
side effect.

Then ask what you deliberately excluded. A clear architecture names both the modules it needs and
the modules it does not.

You are now ready to move from the module map to the next Core lesson: turning application records
into searchable evidence and proving the complete index lifecycle.

## Final Architecture Reference - Do Not Narrate As A List

Use this table to verify on-screen labels and diagrams.

| Requirement | Required AI Fabric pieces | Deliberately separate or optional |
| --- | --- | --- |
| Version alignment | `ai-fabric-bom` | Runtime capabilities |
| Foundation convenience | `ai-fabric-starter` = core + PII + indexing | Providers, RAG, chat-session, governance, behavior |
| Local semantic search | starter + `ai-fabric-onnx-starter` + `ai-fabric-vector-lucene` | LLM generation |
| Managed semantic search | starter + embedding provider + one managed vector provider | RAG until answers are required |
| Evidence-grounded answer | semantic search set + `ai-fabric-rag` + LLM provider | Chat-session unless multi-turn state is required |
| Local governed action | core/starter + application `@AIAction` component | Connector and DB registry modules |
| Durable follow-up and confirmation | governed action set + `ai-fabric-chat-session` | Browser-resubmitted trusted history |
| Behavior analysis | `ai-fabric-behavior` + LLM provider | UI-authored behavioral conclusions |
| Tenant-safe retrieval | trusted application access policy + indexed tenant metadata + filter-capable vector provider | Client-supplied tenant authority |

## Accuracy Guardrails For NotebookLM

- Keep AI Fabric, its application boundary, and its current module names as the subject.
- Do not imply that `ai-fabric-bom` supplies runtime behavior.
- Do not say that `ai-fabric-starter` includes an LLM, embedding provider, vector provider, RAG,
  chat-session, governance, or behavior analysis.
- State that `EnableAIInfrastructure` is optional and that standard Spring Boot auto-configuration
  wires installed modules.
- Do not describe annotations or YAML as proof that indexing or lifecycle synchronization occurred.
- Do not call request position an orchestration mode.
- Do not imply that every pipeline step runs for every request.
- Do not imply that an LLM is required for semantic search.
- Do not imply that Spring AI supplies AI Fabric vector lifecycle and administration behavior.
- Do not let an LLM, browser, or AI Fabric framework service own application authorization or domain
  side effects.
- Do not invent module names, annotations, enum values, methods, configuration properties, endpoints,
  performance numbers, compliance claims, or customer outcomes.
- Do not claim that adding a dependency proves policy, privacy, retrieval, memory, or action behavior.
- Do not present a generated video as execution or test evidence.
