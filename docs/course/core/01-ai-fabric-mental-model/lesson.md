---
id: core-01
slug: mental-model
title: What Is AI Fabric? Architecture And Mental Model
track: core
order: 1
durationMinutes: 70
availability: published
courseVersion: 0.3.3-course.2-beta
frameworkVersion: 0.3.3
frameworkTag: ai-fabric-framework-v0.3.3
courseSourceTag: ai-fabric-course-v0.3.3.2
starterRef: course-0.3.3-00-starter
solutionRef: course-0.3.3-00-starter
requiresOpenAi: false
requiresDocker: false
sourcePaths:
  - docs/course/core/01-ai-fabric-mental-model/notebooklm/AI_FABRIC_INTRO_NOTEBOOKLM_SCRIPT.md
  - docs/course/core/01-ai-fabric-mental-model/notebooklm/AI_FABRIC_ARCHITECTURE_MODULE_MAP_NOTEBOOKLM_SCRIPT.md
  - docs/course/core/01-ai-fabric-mental-model/notebooklm/AI_FABRIC_REQUEST_LIFECYCLE_NOTEBOOKLM_SCRIPT.md
  - docs/course/core/01-ai-fabric-mental-model/notebooklm/AI_FABRIC_CONFIGURATION_EXTENSION_MODEL_NOTEBOOKLM_SCRIPT.md
  - docs/architecture/AI_FABRIC_PUBLIC_ARCHITECTURE.md
  - docs/guides/03-modules.md
  - docs/getting-started/01-choose-your-path.md
  - docs/llm-context/AI_FABRIC_MODULE_DECISION_TREE.md
  - docs/llm-context/AI_FABRIC_CAPABILITY_MAP.md
  - ai-infrastructure-module/ai-fabric-core/src/main/java/ai/fabric/config/OrchestrationProperties.java
theoryVideoIds:
  - what-is-ai-fabric
  - architecture-and-modules
  - request-lifecycle
  - configuration-and-extension-model
assistant:
  mode: analyze
  implementationPrompt: assistant-prompt.md
  reviewPrompt: assistant-review-prompt.md
  validationStatus: passed
knowledgeCheck:
  source: knowledge-check.yml
  required: true
  passingScorePercent: 80
---

# What Is AI Fabric? Architecture And Mental Model

## Start Here

You already know how a Spring Boot application owns HTTP APIs, domain services, repositories,
authorization, and business rules. This lesson shows you where AI Fabric fits without moving those
responsibilities into an LLM, a browser, or the framework.

You will analyze one support application and produce four concrete artifacts:

1. an ownership map;
2. a minimum-module decision table;
3. a retrieval request flow;
4. a confirmation-gated action flow.

There is no code change in this lesson. The output is an architecture contract that you will use in
every later lab.

> **Published analysis lesson.** Use
> [`course-0.3.3-00-starter`](https://github.com/Loom-AI-Labs/ai-fabric-course-support-assistant/tree/course-0.3.3-00-starter)
> as the concrete application shape for both the starter and result. This lesson changes no code;
> its validated output is the architecture contract you carry into CORE-02.

## What You Will Learn

By the end of the lesson, you will be able to:

- explain what AI Fabric does in a Spring Boot application;
- separate application, framework, and provider responsibilities;
- choose the smallest module set for a capability;
- trace retrieval and governed-action requests through their real owners;
- explain why an orchestration mode is different from request position metadata;
- reject designs that let model output directly authorize or execute a write.

## The Application You Will Analyze

Assume you maintain a multi-tenant support application with these existing components:

```text
SupportController
  -> SupportArticleService -> SupportArticleRepository
  -> TicketService         -> TicketRepository
  -> AccountService        -> AccountRepository

AuthenticatedRequestContext
  -> userId
  -> tenantId
  -> roles
```

The product team asks for four capabilities:

1. Users can search support articles by meaning rather than exact keywords.
2. Users can ask a question and receive an answer grounded in approved support evidence.
3. Users can ask to close a ticket, but the write must require confirmation.
4. Follow-up messages such as "yes, close it" must work without the browser resending the complete
   conversation.

Keep this application in view throughout the lesson. AI Fabric adds coordination around these
components. It does not replace them.

## Step 1: Establish The Ownership Boundary

Create a three-column table named `ownership-map.md`. Classify every responsibility before choosing
modules.

Use this completed map as your expected output:

| Your application owns | AI Fabric owns | Configured providers own |
| --- | --- | --- |
| `SupportArticle`, `Ticket`, and account records | Capability contracts and orchestration | LLM inference |
| Repositories and transactions | Entity projection and retrieval coordination | Embedding inference |
| Authenticated `userId`, `tenantId`, and roles | Registered-action discovery | Vector operations behind the selected provider |
| Business authorization | Confirmation and pending-action state | Provider-specific availability and usage reporting |
| The `TicketService.closeTicket` side effect | Backend chat-session integration | Concrete model behavior |
| Public HTTP response and UI projection | Provider selection through configured contracts |  |

Check your map with these questions:

- Could the application enforce the same ticket-closing policy without an LLM?
- Can AI Fabric discover an action without becoming the owner of its transaction?
- Can a provider infer intent without becoming the authority for tenant access?

The correct answer to all three is **yes**.

## Step 2: Decide Whether AI Fabric Is A Good Fit

AI Fabric is a strong fit when the workflow combines model intelligence with application data,
governed operations, memory, privacy, tenant boundaries, or interchangeable providers.

A direct Spring AI or native-provider call may be simpler when you need one isolated model request,
no application evidence, no governed action, no reusable orchestration policy, and no cross-provider
contract.

For the support application, AI Fabric is justified because retrieval, action confirmation, backend
memory, and tenant policy must work together.

### Expected Decision

```text
Decision: use AI Fabric.

Reason:
- approved application evidence must be retrieved;
- one workflow can propose a state-changing action;
- confirmation must survive across requests;
- user and tenant scope must remain application-controlled;
- provider failures must remain visible.
```

An answer such as "use AI Fabric because it calls an LLM" is incomplete. Provider invocation alone
does not justify an application framework.

## Step 3: Choose The Smallest Module Set

Start with the capability you need. Do not install every module.

Complete this decision table:

| Requirement | Required capability group | Deliberately excluded for now |
| --- | --- | --- |
| Semantic article search | BOM, starter/core foundation, one embedding provider, one vector provider | RAG and actions |
| Grounded support answers | Search modules plus RAG and one LLM provider | Behavior analysis |
| Confirmed ticket closing | Action discovery and application action handler | Database action registry unless runtime registration is required |
| Multi-turn confirmation | Chat-session module and application-owned session access policy | Browser-owned conversation replay |
| Tenant-safe retrieval | Application identity and access policy plus tenant metadata filters | Client-supplied tenant authority |

For local development, a valid retrieval profile can use local ONNX embeddings and Lucene vectors.
A live generation profile can add the Spring AI provider bridge and an explicitly selected LLM.
Consumers normally declare `ai-fabric-starter`; it activates the core auto-configuration rather than
requiring the application to assemble core services manually.

### Expected Module Rationale

Your rationale should state why each module is present and why optional modules are absent. For
example:

```text
ai-fabric-rag is present because the response must be grounded in retrieved evidence.
ai-fabric-chat-session is present because confirmation spans multiple requests.
ai-fabric-behavior is absent because this use case does not analyze behavioral events.
The database action registry is absent because actions are registered in this application process.
```

## Step 4: Trace The Retrieval Request

Trace the question: **"How can I recover access after too many failed sign-in attempts?"**

```text
1. The Spring Boot API authenticates the caller.
2. The application supplies trusted user and tenant context.
3. AI Fabric resolves the requested or default orchestration mode.
4. Access policy approves the allowed vector space and metadata scope.
5. The embedding provider converts the approved query text into a vector.
6. The vector provider searches only the allowed evidence scope.
7. AI Fabric returns evidence IDs, scores, content, and metadata.
8. If generation is enabled, the LLM receives only the approved evidence.
9. Output policy sanitizes the result before the application returns it.
10. Allowed conversation state is recorded by the backend session capability.
```

### Expected Retrieval Proof

A grounded response must expose more than fluent wording. You should be able to identify:

- which evidence records were retrieved;
- which tenant and vector-space constraints were applied;
- whether generation ran;
- whether provider or policy failures occurred;
- what response data the application chose to expose.

If no approved evidence exists, the trustworthy state is **no evidence**, not a generic answer
presented as if retrieval succeeded.

## Step 5: Trace The Governed Action

Trace the request: **"Close ticket T-104."**

```text
1. The Spring Boot API authenticates the caller.
2. AI Fabric resolves intent and typed parameters from the natural-language request.
3. Action discovery considers only registered and policy-allowed actions.
4. The application supplies the current user and tenant context.
5. Authorization decides whether this caller may close this ticket.
6. AI Fabric returns a confirmation request instead of executing the write.
7. The pending action is stored in backend conversation state.
8. The user confirms in a later request.
9. AI Fabric resolves the pending action and invokes the registered application handler.
10. The handler calls `TicketService.closeTicket` inside the application's transaction boundary.
11. The application projects a safe action result to the client.
```

### Expected Action Boundary

```text
LLM: understands intent and proposes typed parameters.
AI Fabric: constrains discovery, confirmation, and orchestration.
Application policy: authorizes the current user and target.
TicketService: owns the state change.
UI: displays the proposal, confirmation, and projected result.
```

The LLM never receives authority to write directly to `TicketRepository`.

## Step 6: Separate Mode From Position

An orchestration **mode** is named, typed workflow policy. It can control information handling,
action handling, retrieval behavior, and related orchestration settings.

A request **position** is contextual metadata. It can tell the backend where the request originated,
such as a product page or support panel. It does not activate an orchestration mode by itself.

### Intentional Failure

Review this design:

```text
The browser sends position=resolver.
The backend assumes that means the resolver orchestration mode.
The browser matches "close" and calls TicketRepository directly after a local yes/no dialog.
```

This fails because:

- position metadata is being treated as workflow policy;
- browser keyword matching is manufacturing intelligence;
- confirmation exists only in client state;
- the write bypasses application action authorization and backend pending state.

Replace it with:

```text
The browser sends the user's natural-language message and optional position context.
The backend explicitly selects an allowed named mode.
AI Fabric resolves intent and a registered action.
Application policy authorizes the target.
AI Fabric stores a pending confirmation.
The application action handler owns the confirmed write.
```

## Step 7: Produce Your Architecture Contract

Create `core-01-architecture.md` with these exact sections:

```markdown
# Support Assistant Architecture

## Why AI Fabric
## Application Responsibilities
## AI Fabric Responsibilities
## Provider Responsibilities
## Required Modules
## Deliberately Excluded Modules
## Retrieval Request Flow
## Governed Action Flow
## Security And Tenant Boundary
## Failure Visibility
```

### Expected Final Result

Your document is complete when another developer can answer all of these questions without guessing:

1. Where does authenticated identity originate?
2. Who owns tenant authorization?
3. Which module retrieves evidence?
4. Which provider creates embeddings?
5. Where is confirmation state stored?
6. Which application service performs the write?
7. What happens when evidence or a provider is unavailable?
8. Which optional modules are intentionally absent?

## Common Mistakes

| Mistake | Why it is unsafe or misleading | Correct approach |
| --- | --- | --- |
| Calling a model directly from a controller for every capability | Provider, policy, memory, and failure handling become scattered | Keep application entry points and use AI Fabric capability contracts |
| Treating an LLM response as authorization | Model output is not trusted identity or business policy | Resolve identity and authorization in application-owned policy |
| Letting the browser infer actions from text | UI shortcuts fake intelligence and can bypass backend controls | Send natural language to the backend orchestration path |
| Treating `position` as `mode` | Context metadata does not configure workflow policy | Select a named mode explicitly |
| Installing every module | Increases configuration and operational surface without value | Start from the smallest required capability set |
| Hiding provider failure with a canned answer | The application appears intelligent when the real path failed | Expose a controlled failure or unavailable state |

## Troubleshooting

| What you are unsure about | Inspect this first |
| --- | --- |
| Whether a responsibility belongs to AI Fabric | Ask whether it is reusable orchestration or application business truth |
| Whether to add a module | Start from the capability map and require a specific use case |
| Whether an LLM can execute a write | Find the application action handler and confirmation boundary |
| Whether retrieval is tenant-safe | Find trusted identity, metadata filters, and access-policy tests |
| Whether a flow is genuinely AI-driven | Remove browser keyword rules and trace the backend orchestration result |
| Whether a fallback is acceptable | Confirm it is explicit and does not imitate successful provider output |

## Check Your Result

Before opening the knowledge check, explain these statements in your own words:

1. AI Fabric coordinates application-level AI workflows but does not own domain truth.
2. Providers supply inference and vector operations but do not own authorization.
3. A governed action ends in an application service, not in model output.
4. A named mode is workflow policy; position is request context.
5. Missing evidence or provider failure must remain visible.

## Done When

You are done with this lesson when:

- your ownership map has no repository or authorization responsibility under the LLM;
- your module table contains both required and deliberately excluded modules;
- your retrieval flow identifies evidence and policy boundaries;
- your action flow includes backend confirmation and an application-owned handler;
- you can correct the intentional failure without adding browser intelligence;
- you score at least 80 percent on the knowledge check.

## Next Lesson

In CORE-02, you will turn application records into searchable evidence, preserve stable metadata, and
prove create, update, query, and delete behavior through the vector lifecycle.
