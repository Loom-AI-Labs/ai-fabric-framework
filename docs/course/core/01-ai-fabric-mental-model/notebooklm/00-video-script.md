# NotebookLM Video Script: What Is AI Fabric?

## Production Direction

- Title: **What Is AI Fabric? Why It Exists, When To Use It, And How It Works**
- Target duration: 10-12 minutes.
- Audience: Java and Spring Boot developers who have completed the quickstart or are evaluating AI
  Fabric for an existing application.
- Voice: direct, practical, technically precise, and framework-focused. Address the developer as
  **you**.
- Primary objective: make AI Fabric itself the subject. Do not turn this into a generic AI, RAG, or
  Spring AI explainer.
- Visual style: use architecture diagrams, module groups, request-flow animation, and small reviewed
  Java shapes. Avoid decorative AI imagery and invented APIs.

## Scene 1: Start With The Application You Already Have

**Visual:** A conventional Spring Boot application with controllers, services, repositories, a
database, authorization, and business rules. Add a user request: "Why is my account blocked, and can
you fix it?"

**Narration:**

You already know how to build a Spring Boot application. Your controllers accept requests. Your
services enforce business rules. Your repositories own persistence. Your authorization layer decides
what a user may access.

Then you add AI.

A simple model call can generate text, but a useful application workflow needs more. The model may
need to retrieve your data, understand a follow-up, propose an application action, collect missing
parameters, wait for confirmation, protect sensitive information, respect tenant boundaries, and
leave behind evidence you can test.

AI Fabric exists for that application-level problem.

AI Fabric adds application-level AI capabilities to Spring Boot, including retrieval, governed
actions, memory, privacy, and provider orchestration. It helps you connect model intelligence to real
application data and operations without handing ownership of your domain to a model or a UI shortcut.

## Scene 2: Define AI Fabric Clearly

**Visual:** Put "AI Fabric" in the center. Around it, show Spring Boot Application, LLM and Embedding
Providers, Vector Store, Actions, Memory, and Security Policy.

**Narration:**

AI Fabric is a modular Java and Spring Boot framework for building production-oriented AI workflows
inside applications.

It is not an LLM. It is not a vector database. It is not a chatbot UI. It is not a replacement for
your services, repositories, authorization, or domain model.

AI Fabric supplies the contracts and orchestration between those pieces.

Your application continues to own business truth and side effects. AI Fabric coordinates retrieval,
generation, intent understanding, action discovery, confirmation, conversation state, and policy
checks. Provider modules connect that workflow to LLMs, embedding models, and vector stores.

For LLM and embedding integration, AI Fabric can use its Spring AI provider bridge. For local
embeddings, it can use ONNX. For vectors, it can use local or managed provider modules. The application
works against AI Fabric contracts instead of spreading provider-specific calls across domain code.

## Scene 3: Why The Framework Was Built

**Visual:** First show scattered code: provider calls in controllers, prompt strings in services,
browser keyword rules, direct repository writes from model output, and duplicated session handling.
Then regroup those concerns behind AI Fabric boundaries.

**Narration:**

Without a framework boundary, AI features often grow as disconnected integrations.

A controller calls one model SDK. Another service formats prompts. The browser guesses intent from
keywords. A confirmation is held only in UI state. Retrieval trusts a client-supplied tenant ID. A
provider failure is hidden by a canned answer. Tests verify mocked wording but not evidence or side
effects.

Each individual shortcut can look reasonable. Together they make the workflow difficult to secure,
change, and prove.

AI Fabric was built to make these concerns explicit and reusable:

- provider calls behind stable Java contracts;
- application data projected into retrievable evidence;
- actions registered around application services;
- confirmation before governed writes;
- backend-owned conversation state;
- access and tenant policy before retrieval or execution;
- PII handling before sensitive text crosses a boundary;
- deterministic and live-provider tests that expose failure instead of hiding it.

The goal is not to make every AI application identical. The goal is to give Java teams a consistent
foundation while preserving application ownership.

## Scene 4: Understand The Ownership Boundary

**Visual:** A two-column ownership map. Left: Your Application. Right: AI Fabric And Providers.

**Narration:**

The most important AI Fabric design rule is the ownership boundary.

Your application owns domain records, source-of-truth persistence, business policy, authorization,
tenant identity, side-effect implementation, and the API presented to its users.

AI Fabric owns framework contracts, orchestration, entity processing, retrieval coordination, action
discovery, confirmation state, session integration, and provider selection.

The configured providers own model inference and vector operations. An LLM can understand a request
or propose an allowed action, but it does not directly own a repository write. A browser can present a
suggestion or confirmation, but it does not manufacture intent or inject a privileged target.

This split lets you use model intelligence without treating model output as authority.

## Scene 5: See The Architecture In Layers

**Visual:** Build this five-layer diagram from bottom to top.

```text
5. Application UI and APIs
4. Application domain services, repositories, identity, and policy
3. AI Fabric capability modules and orchestration
2. AI Fabric provider and vector contracts
1. LLMs, embedding models, vector stores, and application databases
```

**Narration:**

You can understand AI Fabric as a set of layers.

At the application boundary, your UI or API sends natural-language input and structured request
context to your Spring Boot backend.

Your application contributes domain services, repositories, authenticated identity, tenant scope,
and readable business policy.

AI Fabric capability modules coordinate the workflow. Retrieval modules find evidence. RAG modules
prepare grounded generation. Action modules discover registered operations. Chat-session modules
persist turns and pending confirmations. Governance and PII modules enforce declared boundaries.

Provider contracts sit beneath those capabilities. They isolate the workflow from a specific LLM,
embedding service, or vector store.

At the bottom are the concrete systems: OpenAI, Azure OpenAI, Anthropic, Gemini, local ONNX models,
Lucene, memory storage, Qdrant, Pinecone, Weaviate, Milvus, and your application database.

The framework does not erase these systems. It makes their role and failure visible through a
consistent application architecture.

## Scene 6: Choose Modules By Capability

**Visual:** Show module groups as a toolbox. Highlight only the group used by each example.

**Narration:**

AI Fabric is modular. Start with the smallest capability that proves value, then add modules as the
application needs them.

The foundation is the AI Fabric BOM, `ai-fabric-core`, and `ai-fabric-starter`. The BOM keeps module
versions aligned. Core supplies the main contracts and orchestration primitives. The starter activates
Spring Boot auto-configuration.

For semantic search, add an embedding provider, one vector provider, and optionally
`ai-fabric-indexing`. Local development can combine `ai-fabric-onnx-starter` with
`ai-fabric-vector-lucene`.

For grounded answers, add `ai-fabric-rag` and an LLM provider, commonly
`ai-fabric-provider-spring-ai`.

For application operations, use local AI action discovery or add connector and database-backed
action-registry modules. Add `ai-fabric-chat-session` when confirmations and follow-up turns must
survive beyond one request.

For sensitive or shared environments, add PII and governance capabilities, application-owned access
policy, and metadata-scoped retrieval.

Behavior analysis, relationship queries, data synchronization, migration, curated prompt packs, and
managed vector providers remain optional. Do not install them merely because they exist.

## Scene 7: Trace A Retrieval Request

**Visual:** Animate the public architecture request flow.

```text
User
  -> Spring Boot API
  -> AI Fabric orchestration
  -> access and tenant policy
  -> embedding + vector retrieval
  -> approved evidence
  -> optional LLM generation
  -> application response
```

**Narration:**

Consider a user asking, "Why can I not place an order?"

Your backend receives the authenticated request. Application policy supplies the current user and
tenant boundary. AI Fabric determines the configured workflow and retrieves approved account or
policy evidence through its contracts. If the mode includes generation, the provider receives the
allowed evidence and produces a response. AI Fabric returns structured workflow results to your
application, which decides how to expose them.

The important point is not that every request uses every step. The point is that identity, policy,
retrieval, generation, and presentation have explicit owners.

If evidence is missing, the workflow should expose that state. It should not replace missing evidence
with generic model knowledge and pretend retrieval worked.

## Scene 8: Trace A Governed Action

**Visual:** Show a request to update payment details. Pause at confirmation before execution.

```text
User request
  -> intent and parameter understanding
  -> allowed action discovery
  -> missing-parameter collection
  -> confirmation proposal
  -> user confirms
  -> application action handler
  -> structured action result
```

**Narration:**

Now consider, "Update my payment method."

AI Fabric can use the LLM to understand the intent and collect typed parameters. It discovers only
actions registered and allowed for the current workflow. If required information is missing, the
conversation continues. If the action changes state, AI Fabric returns a confirmation request instead
of executing immediately.

After the user confirms, an application-owned action handler invokes the real domain service. The
result comes back as structured data that the application can safely project to the UI.

The LLM helps understand what the user wants. Configuration and application policy constrain what is
possible. Application code owns the side effect.

## Scene 9: Memory, Privacy, And Tenant Safety

**Visual:** Three guarded lanes labeled Conversation State, PII Boundary, and Tenant Scope.

**Narration:**

Real conversations extend beyond one message.

When a user says "yes," "add it," or "compare those," the backend needs previous turns and pending
state. The `ai-fabric-chat-session` module gives the application a backend-owned conversation history
instead of requiring the browser to resend trusted context.

Sensitive text needs a declared privacy policy. The PII module can detect and redact information
before indexing, logging, or provider calls according to application configuration. A failure should
remain visible; privacy should not be simulated with UI masking alone.

Multi-tenant retrieval needs authenticated tenant context, metadata-scoped indexing, provider filter
support, and fail-closed access policy. Never trust a tenant or account ID merely because the browser
sent it.

These are application architecture concerns, not prompt-writing tricks.

## Scene 10: Know When To Use AI Fabric

**Visual:** Two columns: Good Fit and Simpler Alternative.

**Narration:**

AI Fabric is a strong fit when you are adding AI workflows to a Java or Spring Boot application and
you need application data, governed actions, conversation state, privacy, tenant boundaries, provider
choice, or release-ready tests.

It is especially useful when the AI must interact with existing domain services rather than operate
as an isolated chatbot.

You may not need AI Fabric for a one-off script, an experiment that only sends one prompt to one
model, or an application with no need for framework-level retrieval, governance, memory, or provider
abstraction. A direct Spring AI or provider integration may be the smaller and clearer choice there.

Use the framework when its boundaries remove real application complexity. Do not add modules without
a capability that needs them.

## Scene 11: Use The Course Mental Model

**Visual:** Show the course path: Quickstart -> Core -> Production -> Case Studies -> Capstone.

**Narration:**

The quickstart gave you one small proof: application records became retrievable evidence through AI
Fabric.

The Core track now expands that proof. You will model and index data, generate answers from evidence,
register governed actions, preserve conversation state, enforce tenant and privacy boundaries, and
test the complete vertical slice.

The Production track then adds provider profiles, backfill, retrieval quality, managed vector stores,
and release operations. Real-application case studies show how these boundaries behave under actual
failure and correction.

For every feature, ask the same four questions:

1. What remains application-owned?
2. Which AI Fabric capability and module is actually required?
3. Which provider or storage boundary executes the operation?
4. What evidence proves the workflow behaved correctly?

If you can answer those questions, you can add AI capability without losing control of the
application around it.

## Scene 12: Final Summary

**Visual:** Return to the central AI Fabric diagram, now with all boundaries labeled.

**Narration:**

AI Fabric is the application-level coordination layer between your Spring Boot domain and the AI
systems you choose.

It gives you modular contracts for retrieval, grounded generation, governed actions, memory, privacy,
tenant safety, behavior analysis, and provider-backed orchestration. It keeps domain truth and side
effects in application code while making model intelligence useful inside real workflows.

Use it when your AI feature must become part of an application, not remain a disconnected model call.

Next, map one requirement from your own Spring Boot application to the smallest AI Fabric module set,
and identify one responsibility that must stay in ordinary application code.

## Accuracy Guardrails For NotebookLM

- Keep AI Fabric as the central subject in every scene.
- Do not describe AI Fabric as an LLM, vector database, chatbot UI, or deployment platform.
- Do not imply that AI Fabric replaces Spring AI, application services, repositories, authorization,
  or domain policy.
- Do not claim every request uses RAG, actions, memory, PII, or every module.
- Do not imply that an LLM directly executes repository writes.
- Do not invent annotations, methods, configuration properties, module names, or provider support.
- Do not hide provider, access-control, retrieval, or PII failures behind fallback wording.
- Do not introduce performance, accuracy, compliance, adoption, or uptime claims.
- Use only capabilities and module names present in the supplied source pack.
